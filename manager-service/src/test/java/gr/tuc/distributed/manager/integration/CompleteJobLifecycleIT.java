package gr.tuc.distributed.manager.integration;

import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.FileMetadata;
import gr.tuc.distributed.manager.entity.Job;
import gr.tuc.distributed.manager.entity.Task;
import gr.tuc.distributed.manager.k8s.KubernetesJobLauncher;
import gr.tuc.distributed.manager.minio.MinioStorageService;
import gr.tuc.distributed.manager.repository.FileMetadataRepository;
import gr.tuc.distributed.manager.repository.JobRepository;
import gr.tuc.distributed.manager.repository.TaskRepository;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import gr.tuc.distributed.common.dto.JobStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Full end-to-end job lifecycle test (Section 8.1 — testCompleteJobLifecycle).
 * <ol>
 *   <li>Upload data to MinIO</li>
 *   <li>Upload code to MinIO</li>
 *   <li>Submit job — verify MAP_PHASE</li>
 *   <li>Simulate map tasks completing — verify REDUCE_PHASE</li>
 *   <li>Simulate reduce tasks completing — verify COMPLETED</li>
 *   <li>Query job status — verify output is populated</li>
 * </ol>
 * Uses real Postgres + MinIO, mocks only the Kubernetes launcher.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class CompleteJobLifecycleIT extends TestContainersBase {

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
        userId = "lifecycle-" + UUID.randomUUID();

        // Stub K8s launcher
        when(k8sLauncher.launchWorker(any(), any(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "mock-pod-" + inv.getArgument(0));

        // Upload a real data file to MinIO
        String dataPath = "users/" + userId + "/raw/input.txt";
        minioService.upload(dataPath,
                new ByteArrayInputStream("hello world mapreduce test data".getBytes()),
                31, "text/plain");

        dataFile = new FileMetadata();
        dataFile.setUserId(userId);
        dataFile.setFileType("DATA");
        dataFile.setOriginalName("input.txt");
        dataFile.setStoragePath(dataPath);
        dataFile = fileMetadataRepository.save(dataFile);

        // Upload a real code file to MinIO
        String codePath = "users/" + userId + "/code/wordcount.jar";
        minioService.upload(codePath,
                new ByteArrayInputStream(new byte[100]),
                100, "application/java-archive");

        codeFile = new FileMetadata();
        codeFile.setUserId(userId);
        codeFile.setFileType("CODE");
        codeFile.setOriginalName("wordcount.jar");
        codeFile.setStoragePath(codePath);
        codeFile = fileMetadataRepository.save(codeFile);
    }

    @Test
    void testCompleteJobLifecycle() {
        // 1 & 2. Files already uploaded in setUp

        // 3. Submit job
        var request = new gr.tuc.distributed.common.dto.JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(2);

        UUID jobId = orchestrationService.submitJob(request, userId);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.MAP_PHASE);
        assertThat(job.getUserId()).isEqualTo(userId);

        // 4. Simulate all map tasks completing
        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);
        assertThat(mapTasks).isNotEmpty();

        for (Task t : mapTasks) {
            // Simulate map output: upload an intermediate file and report completion
            String mapOutput = "users/temp/" + jobId + "/map-" + t.getTaskId() + "/part-0.txt";
            minioService.upload(mapOutput,
                    new ByteArrayInputStream("word\t1".getBytes()),
                    6, "text/plain");

            TaskStatusUpdate update = new TaskStatusUpdate();
            update.setStatus(TaskStatus.COMPLETED);
            update.setOutputLocation(mapOutput);
            orchestrationService.handleTaskUpdate(t.getTaskId(), update);
        }

        job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.REDUCE_PHASE);

        // 5. Simulate all reduce tasks completing
        List<Task> reduceTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.REDUCE);
        assertThat(reduceTasks).isNotEmpty();
        assertThat(reduceTasks).hasSizeLessThanOrEqualTo(2);

        for (Task t : reduceTasks) {
            String reduceOutput = "users/" + userId + "/results/" + jobId + "/reduce-" + t.getTaskId() + ".txt";
            minioService.upload(reduceOutput,
                    new ByteArrayInputStream("word\t3".getBytes()),
                    6, "text/plain");

            TaskStatusUpdate update = new TaskStatusUpdate();
            update.setStatus(TaskStatus.COMPLETED);
            update.setOutputLocation(reduceOutput);
            orchestrationService.handleTaskUpdate(t.getTaskId(), update);
        }

        // 6. Verify final state
        job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getOutputPath()).isNotNull();

        // Verify output path contains reduce results
        JobStatusResponse status = orchestrationService.getJobStatus(jobId, userId);
        assertThat(status.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(status.getOutputUrls()).isNotNull();
        assertThat(status.getOutputUrls()).isNotEmpty();
    }
}
