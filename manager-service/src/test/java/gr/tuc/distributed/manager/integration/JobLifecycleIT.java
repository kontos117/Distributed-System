package gr.tuc.distributed.manager.integration;

import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.FileMetadata;
import gr.tuc.distributed.manager.entity.Job;
import gr.tuc.distributed.manager.entity.Task;
import gr.tuc.distributed.manager.k8s.KubernetesJobLauncher;
import gr.tuc.distributed.manager.repository.FileMetadataRepository;
import gr.tuc.distributed.manager.repository.JobRepository;
import gr.tuc.distributed.manager.repository.TaskRepository;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import gr.tuc.distributed.manager.minio.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test: full job lifecycle against real Postgres + MinIO.
 * The Kubernetes client is mocked — we verify state transitions in the DB.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class JobLifecycleIT extends TestContainersBase {

    @Autowired JobOrchestrationService orchestrationService;
    @Autowired JobRepository            jobRepository;
    @Autowired TaskRepository           taskRepository;
    @Autowired FileMetadataRepository   fileMetadataRepository;
    @Autowired MinioStorageService      minioService;

    // Mock the K8s launcher so no actual pods are spawned
    @MockBean KubernetesJobLauncher k8sLauncher;

    private String userId;
    private FileMetadata dataFile;
    private FileMetadata codeFile;

    @BeforeEach
    void setUp() {
        userId = "user-" + UUID.randomUUID();

        // Stub the launcher — return a predictable pod name
        when(k8sLauncher.launchWorker(any(), any(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "mock-pod-" + inv.getArgument(0));

        String dataPath = "users/" + userId + "/raw/input.txt";
        minioService.upload(dataPath, new java.io.ByteArrayInputStream("data".getBytes()), 4, "text/plain");

        dataFile = new FileMetadata();
        dataFile.setUserId(userId);
        dataFile.setFileType("DATA");
        dataFile.setOriginalName("input.txt");
        dataFile.setStoragePath(dataPath);
        dataFile = fileMetadataRepository.save(dataFile);

        String codePath = "users/" + userId + "/code/wordcount.jar";
        minioService.upload(codePath, new java.io.ByteArrayInputStream("code".getBytes()), 4, "application/java-archive");

        codeFile = new FileMetadata();
        codeFile.setUserId(userId);
        codeFile.setFileType("CODE");
        codeFile.setOriginalName("wordcount.jar");
        codeFile.setStoragePath(codePath);
        codeFile = fileMetadataRepository.save(codeFile);
    }

    @Test
    void jobStartsInMapPhase() {
        var request = new gr.tuc.distributed.common.dto.JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(2);

        UUID jobId = orchestrationService.submitJob(request, userId);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.MAP_PHASE);
        assertThat(job.getNumReduceTasks()).isEqualTo(2);

        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);
        assertThat(mapTasks).isNotEmpty();
        assertThat(mapTasks).allMatch(t -> t.getStatus() == TaskStatus.IN_PROGRESS);
    }

    @Test
    void allMapTasksCompletedTransitionsToReducePhase() {
        var request = new gr.tuc.distributed.common.dto.JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(1);

        UUID jobId = orchestrationService.submitJob(request, userId);

        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);
        for (Task t : mapTasks) {
            TaskStatusUpdate update = new TaskStatusUpdate();
            update.setStatus(TaskStatus.COMPLETED);
            update.setOutputLocation("users/temp/" + jobId + "/map-" + t.getTaskId() + "/part-0.txt");
            orchestrationService.handleTaskUpdate(t.getTaskId(), update);
        }

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.REDUCE_PHASE);

        List<Task> reduceTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.REDUCE);
        assertThat(reduceTasks).hasSize(1);
        assertThat(reduceTasks.get(0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void allReduceTasksCompletedMarksJobCompleted() {
        var request = new gr.tuc.distributed.common.dto.JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(1);

        UUID jobId = orchestrationService.submitJob(request, userId);

        taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP)
                .forEach(t -> {
                    TaskStatusUpdate u = new TaskStatusUpdate();
                    u.setStatus(TaskStatus.COMPLETED);
                    u.setOutputLocation("users/temp/" + jobId + "/map-" + t.getTaskId() + "/part-0.txt");
                    orchestrationService.handleTaskUpdate(t.getTaskId(), u);
                });

        taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.REDUCE)
                .forEach(t -> {
                    TaskStatusUpdate u = new TaskStatusUpdate();
                    u.setStatus(TaskStatus.COMPLETED);
                    u.setOutputLocation("users/" + userId + "/results/" + jobId + "/reduce-" + t.getTaskId() + ".txt");
                    orchestrationService.handleTaskUpdate(t.getTaskId(), u);
                });

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void failedTaskIsRetriedUpToThreeTimes() {
        var request = new gr.tuc.distributed.common.dto.JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(1);

        UUID jobId = orchestrationService.submitJob(request, userId);

        Task mapTask = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP).get(0);

        for (int i = 0; i < 4; i++) {
            TaskStatusUpdate fail = new TaskStatusUpdate();
            fail.setStatus(TaskStatus.FAILED);
            fail.setErrorMessage("simulated failure " + i);
            orchestrationService.handleTaskUpdate(mapTask.getTaskId(), fail);

            Task updated = taskRepository.findById(mapTask.getTaskId()).orElseThrow();
            if (i < 3) {
                // Should be retried — back to IN_PROGRESS
                assertThat(updated.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            }
        }

        // After 3 failures the job should be FAILED
        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void heartbeatUpdatesLastHeartbeatTimestamp() {
        var request = new gr.tuc.distributed.common.dto.JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(1);

        UUID jobId = orchestrationService.submitJob(request, userId);
        Task task = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP).get(0);

        assertThat(task.getLastHeartbeat()).isNull();

        orchestrationService.recordHeartbeat(task.getTaskId());

        Task updated = taskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(updated.getLastHeartbeat()).isNotNull();
    }
}
