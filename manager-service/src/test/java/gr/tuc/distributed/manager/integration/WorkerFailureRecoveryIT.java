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
 * Integration test for worker failure and recovery (Section 8.1 — testWorkerFailure).
 * Verifies:
 * <ul>
 *   <li>Task reassignment after a worker crash (retry up to 3 times)</li>
 *   <li>Job still completes if a failed task eventually succeeds</li>
 *   <li>Job is marked FAILED if a task permanently fails</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class WorkerFailureRecoveryIT extends TestContainersBase {

    @Autowired JobOrchestrationService orchestrationService;
    @Autowired JobRepository           jobRepository;
    @Autowired TaskRepository          taskRepository;
    @Autowired FileMetadataRepository  fileMetadataRepository;
    @Autowired MinioStorageService     minioService;

    @MockBean KubernetesJobLauncher k8sLauncher;

    private String userId;
    private FileMetadata dataFile;
    private FileMetadata codeFile;

    @BeforeEach
    void setUp() {
        userId = "worker-fail-" + UUID.randomUUID();

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
    void failedTaskIsRetriedAndJobCompletesIfEventuallySucceeds() {
        UUID jobId = submitJob();

        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);
        Task firstTask = mapTasks.get(0);

        // Fail the first map task twice — should be retried each time
        for (int i = 0; i < 2; i++) {
            TaskStatusUpdate fail = new TaskStatusUpdate();
            fail.setStatus(TaskStatus.FAILED);
            fail.setErrorMessage("simulated crash #" + i);
            orchestrationService.handleTaskUpdate(firstTask.getTaskId(), fail);

            Task updated = taskRepository.findById(firstTask.getTaskId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(updated.getRetryCount()).isEqualTo(i + 1);
        }

        // Now let it succeed on the third attempt
        TaskStatusUpdate success = new TaskStatusUpdate();
        success.setStatus(TaskStatus.COMPLETED);
        success.setOutputLocation("users/temp/" + jobId + "/map-" + firstTask.getTaskId() + "/part-0.txt");
        orchestrationService.handleTaskUpdate(firstTask.getTaskId(), success);

        // Complete remaining map tasks
        for (Task t : mapTasks) {
            if (t.getTaskId().equals(firstTask.getTaskId())) continue;
            TaskStatusUpdate u = new TaskStatusUpdate();
            u.setStatus(TaskStatus.COMPLETED);
            u.setOutputLocation("users/temp/" + jobId + "/map-" + t.getTaskId() + "/part-0.txt");
            orchestrationService.handleTaskUpdate(t.getTaskId(), u);
        }

        // Should have transitioned to REDUCE_PHASE
        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.REDUCE_PHASE);

        // Complete reduce tasks
        taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.REDUCE)
                .forEach(t -> {
                    TaskStatusUpdate u = new TaskStatusUpdate();
                    u.setStatus(TaskStatus.COMPLETED);
                    u.setOutputLocation("users/" + userId + "/results/" + jobId + "/reduce-" + t.getTaskId() + ".txt");
                    orchestrationService.handleTaskUpdate(t.getTaskId(), u);
                });

        // Job should be COMPLETED despite the initial failures
        job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void taskPermanentlyFailsAfterThreeRetries_jobMarkedFailed() {
        UUID jobId = submitJob();

        Task mapTask = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP).get(0);

        // Fail it 4 times
        for (int i = 0; i < 4; i++) {
            TaskStatusUpdate fail = new TaskStatusUpdate();
            fail.setStatus(TaskStatus.FAILED);
            fail.setErrorMessage("permanent failure #" + i);
            orchestrationService.handleTaskUpdate(mapTask.getTaskId(), fail);
        }

        // Job should now be FAILED
        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("permanently failed");
    }

    @Test
    void multipleTasksFail_eachIsRetriedIndependently() {
        UUID jobId = submitJob();

        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);

        // Fail each task once — all should be retried
        for (Task t : mapTasks) {
            TaskStatusUpdate fail = new TaskStatusUpdate();
            fail.setStatus(TaskStatus.FAILED);
            fail.setErrorMessage("single failure");
            orchestrationService.handleTaskUpdate(t.getTaskId(), fail);

            Task updated = taskRepository.findById(t.getTaskId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(updated.getRetryCount()).isEqualTo(1);
        }

        // Now complete all of them
        for (Task t : mapTasks) {
            TaskStatusUpdate success = new TaskStatusUpdate();
            success.setStatus(TaskStatus.COMPLETED);
            success.setOutputLocation("users/temp/" + jobId + "/map-" + t.getTaskId() + "/part-0.txt");
            orchestrationService.handleTaskUpdate(t.getTaskId(), success);
        }

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.REDUCE_PHASE);
    }

    // ---- helpers ----

    private UUID submitJob() {
        var request = new gr.tuc.distributed.common.dto.JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(1);
        return orchestrationService.submitJob(request, userId);
    }
}
