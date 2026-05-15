package gr.tuc.distributed.manager.chaos;

import gr.tuc.distributed.common.dto.JobSubmitRequest;
import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.FileMetadata;
import gr.tuc.distributed.manager.entity.Job;
import gr.tuc.distributed.manager.entity.Task;
import gr.tuc.distributed.manager.integration.TestContainersBase;
import gr.tuc.distributed.manager.k8s.KubernetesJobLauncher;
import gr.tuc.distributed.manager.minio.MinioStorageService;
import gr.tuc.distributed.manager.repository.FileMetadataRepository;
import gr.tuc.distributed.manager.repository.JobRepository;
import gr.tuc.distributed.manager.repository.TaskRepository;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.junit.jupiter.api.Disabled;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Chaos Engineering tests (Section 8.2 of the design document).
 * Simulates:
 * <ul>
 *   <li>Random pod killing — 30% of map tasks are failed randomly</li>
 *   <li>MinIO service disruption — container stopped mid-job</li>
 *   <li>Database connection pool exhaustion — concurrent job submissions</li>
 * </ul>
 * All tests run against real Postgres + MinIO containers via Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class ChaosEngineeringIT extends TestContainersBase {

    @Autowired JobOrchestrationService orchestrationService;
    @Autowired JobRepository           jobRepository;
    @Autowired TaskRepository          taskRepository;
    @Autowired FileMetadataRepository  fileMetadataRepository;
    @Autowired MinioStorageService     minioService;

    @MockBean KubernetesJobLauncher k8sLauncher;

    private String userId;
    private FileMetadata dataFile;
    private FileMetadata codeFile;
    private final Random random = new Random(42); // fixed seed for reproducibility

    @BeforeEach
    void setUp() {
        userId = "chaos-" + UUID.randomUUID();

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

    // ═══════════════════════════════════════════════════════════════════
    // 1. Random Pod Killing (30% of tasks)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Chaos: 30% of map tasks crash randomly — job completes via retry")
    void randomPodKilling_jobStillCompletesThroughRetries() {
        UUID jobId = submitJob(1);

        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);
        assertThat(mapTasks).isNotEmpty();

        // Randomly fail ~30% of tasks
        for (Task t : mapTasks) {
            if (random.nextDouble() < 0.30) {
                TaskStatusUpdate fail = new TaskStatusUpdate();
                fail.setStatus(TaskStatus.FAILED);
                fail.setErrorMessage("Chaos: simulated pod kill");
                orchestrationService.handleTaskUpdate(t.getTaskId(), fail);

                // Verify it was retried (status back to IN_PROGRESS)
                Task updated = taskRepository.findById(t.getTaskId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
                assertThat(updated.getRetryCount()).isGreaterThan(0);
            }
        }

        // Now let all tasks succeed
        List<Task> currentTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);
        for (Task t : currentTasks) {
            if (t.getStatus() != TaskStatus.COMPLETED) {
                TaskStatusUpdate success = new TaskStatusUpdate();
                success.setStatus(TaskStatus.COMPLETED);
                success.setOutputLocation("users/temp/" + jobId + "/map-" + t.getTaskId() + "/part-0.txt");
                orchestrationService.handleTaskUpdate(t.getTaskId(), success);
            }
        }

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.REDUCE_PHASE);

        // Complete reduce
        taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.REDUCE).forEach(t -> {
            TaskStatusUpdate u = new TaskStatusUpdate();
            u.setStatus(TaskStatus.COMPLETED);
            u.setOutputLocation("users/" + userId + "/results/" + jobId + "/reduce-" + t.getTaskId() + ".txt");
            orchestrationService.handleTaskUpdate(t.getTaskId(), u);
        });

        job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. MinIO Service Disruption
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Chaos: MinIO container stopped — detects failure and recovers after restart")
    @Disabled("Stopping the singleton MinIO container breaks port bindings for other tests. Use Toxiproxy instead.")
    void minioDisruption_detectsFailureAndRecovers() {
        // First verify MinIO is working
        minioService.upload("chaos-test/healthy-check.txt",
                new java.io.ByteArrayInputStream("alive".getBytes()), 5, "text/plain");

        // Stop MinIO container to simulate an outage
        MINIO.stop();

        // Verify that operations fail while MinIO is down
        boolean failedAsExpected = false;
        try {
            minioService.upload("chaos-test/should-fail.txt",
                    new java.io.ByteArrayInputStream("fail".getBytes()), 4, "text/plain");
        } catch (Exception e) {
            failedAsExpected = true;
        }
        assertThat(failedAsExpected).as("MinIO operation should fail when container is stopped").isTrue();

        // Restart MinIO and verify recovery
        MINIO.start();

        // Wait for MinIO to be ready again
        org.testcontainers.Testcontainers.exposeHostPorts(MINIO.getMappedPort(9000));

        // Upload should work again after restart
        minioService.upload("chaos-test/recovered.txt",
                new java.io.ByteArrayInputStream("recovered".getBytes()), 9, "text/plain");

        String downloaded;
        try (var is = minioService.download("chaos-test/recovered.txt")) {
            downloaded = new String(is.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(downloaded).isEqualTo("recovered");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. Database Connection Pool Exhaustion
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Chaos: concurrent job submissions — handles contention without deadlocks")
    void concurrentJobSubmissions_handlesWithoutDeadlocks() throws Exception {
        int concurrency = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<UUID>> futures = new ArrayList<>();

        // Submit multiple jobs concurrently — each with unique user files
        for (int i = 0; i < concurrency; i++) {
            String concurrentUserId = "concurrent-" + UUID.randomUUID();

            String dPath = "users/" + concurrentUserId + "/raw/input-" + i + ".txt";
            minioService.upload(dPath, new java.io.ByteArrayInputStream("data".getBytes()), 4, "text/plain");

            FileMetadata df = new FileMetadata();
            df.setUserId(concurrentUserId);
            df.setFileType("DATA");
            df.setOriginalName("input-" + i + ".txt");
            df.setStoragePath(dPath);
            df = fileMetadataRepository.save(df);

            String cPath = "users/" + concurrentUserId + "/code/wc-" + i + ".jar";
            minioService.upload(cPath, new java.io.ByteArrayInputStream("code".getBytes()), 4, "application/java-archive");

            FileMetadata cf = new FileMetadata();
            cf.setUserId(concurrentUserId);
            cf.setFileType("CODE");
            cf.setOriginalName("wc-" + i + ".jar");
            cf.setStoragePath(cPath);
            cf = fileMetadataRepository.save(cf);

            final FileMetadata finalDf = df;
            final FileMetadata finalCf = cf;
            final String uid = concurrentUserId;

            futures.add(executor.submit(() -> {
                JobSubmitRequest req = new JobSubmitRequest();
                req.setDataId(finalDf.getFileId().toString());
                req.setCodeId(finalCf.getFileId().toString());
                req.setNumReducers(1);
                return orchestrationService.submitJob(req, uid);
            }));
        }

        // Collect results — no exceptions should be thrown
        List<UUID> jobIds = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();

        for (Future<UUID> f : futures) {
            try {
                jobIds.add(f.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                errors.add(e);
            }
        }
        executor.shutdown();

        // All submissions should succeed (pool contention should not cause failures)
        assertThat(errors)
                .as("No job submissions should fail due to DB contention")
                .isEmpty();
        assertThat(jobIds).hasSize(concurrency);

        // Verify each job was created in MAP_PHASE
        for (UUID id : jobIds) {
            Job job = jobRepository.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(JobStatus.MAP_PHASE);
        }
    }

    @Test
    @DisplayName("Chaos: concurrent task updates on same job — no lost updates")
    void concurrentTaskUpdates_noLostUpdates() throws Exception {
        UUID jobId = submitJob(1);
        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP);

        ExecutorService executor = Executors.newFixedThreadPool(mapTasks.size());
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // All tasks complete concurrently
        for (Task t : mapTasks) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await(); // synchronize all threads to start at the same time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                TaskStatusUpdate update = new TaskStatusUpdate();
                update.setStatus(TaskStatus.COMPLETED);
                update.setOutputLocation("users/temp/" + jobId + "/map-" + t.getTaskId() + "/part-0.txt");
                orchestrationService.handleTaskUpdate(t.getTaskId(), update);
            }));
        }

        latch.countDown(); // release all threads

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // All map tasks should be completed
        long completedMaps = taskRepository.countByJobJobIdAndStatus(jobId, TaskStatus.COMPLETED);
        assertThat(completedMaps).isEqualTo(mapTasks.size());

        // Job should have transitioned to REDUCE_PHASE
        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isIn(JobStatus.REDUCE_PHASE, JobStatus.COMPLETED);
    }

    // ---- helpers ----

    private UUID submitJob(int numReducers) {
        JobSubmitRequest request = new JobSubmitRequest();
        request.setDataId(dataFile.getFileId().toString());
        request.setCodeId(codeFile.getFileId().toString());
        request.setNumReducers(numReducers);
        return orchestrationService.submitJob(request, userId);
    }
}
