package gr.tuc.distributed.manager.service;

import gr.tuc.distributed.common.dto.JobStatusResponse;
import gr.tuc.distributed.common.dto.JobSubmitRequest;
import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.Job;
import gr.tuc.distributed.manager.entity.Task;
import gr.tuc.distributed.manager.k8s.KubernetesJobLauncher;
import gr.tuc.distributed.manager.minio.MinioStorageService;
import gr.tuc.distributed.manager.repository.FileMetadataRepository;
import gr.tuc.distributed.manager.repository.JobRepository;
import gr.tuc.distributed.manager.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobOrchestrationService {

    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final KubernetesJobLauncher k8sLauncher;
    private final MinioStorageService minioService;

    @Value("${mapreduce.default-map-tasks:4}")
    private int defaultMapTasks;

    // ----------------------------------------------------------------
    // Submit a new job
    // ----------------------------------------------------------------

    @Transactional
    public UUID submitJob(JobSubmitRequest request, String userId) {
        var dataFile = fileMetadataRepository
                .findByFileIdAndUserId(UUID.fromString(request.getDataId()), userId)
                .orElseThrow(() -> new NoSuchElementException("Data file not found: " + request.getDataId()));

        var codeFile = fileMetadataRepository
                .findByFileIdAndUserId(UUID.fromString(request.getCodeId()), userId)
                .orElseThrow(() -> new NoSuchElementException("Code file not found: " + request.getCodeId()));

        Job job = new Job();
        job.setUserId(userId);
        job.setStatus(JobStatus.INITIALIZING);
        job.setInputPath(dataFile.getStoragePath());
        job.setCodePath(codeFile.getStoragePath());
        job.setNumMapTasks(defaultMapTasks);
        job.setNumReduceTasks(request.getNumReducers());

        //save first so the DB assigns the jobId UUID
        job = jobRepository.save(job);

        // now set outputPath using the assigned jobId
        job.setOutputPath("users/" + userId + "/results/" + job.getJobId());
        job = jobRepository.save(job);

        log.info("Job {} created for user {}", job.getJobId(), userId);

        // Kick off map phase asynchronously
        startMapPhase(job);

        return job.getJobId();
    }

    // ----------------------------------------------------------------
    // Cancel a job
    // ----------------------------------------------------------------

    @Transactional
    public void cancelJob(UUID jobId, String userId) {
        Job job = jobRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));

        JobStatus status = job.getStatus();
        if (status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED) {
            throw new IllegalStateException("Job " + jobId + " is already in terminal state: " + status);
        }

        // delete any running K8s worker Jobs
        taskRepository.findByJobJobId(jobId).stream()
                .filter(t -> t.getWorkerPodId() != null)
                .forEach(t -> {
                    try { k8sLauncher.deleteJob(t.getWorkerPodId()); }
                    catch (Exception e) { log.warn("Could not delete K8s Job {}: {}", t.getWorkerPodId(), e.getMessage()); }
                });

        job.setStatus(JobStatus.CANCELLED);
        jobRepository.save(job);
        log.info("Job {} cancelled by user {}", jobId, userId);
    }

    // ----------------------------------------------------------------
    // Task status callback (called by workers via POST /internal/tasks/{id}/status)
    // ----------------------------------------------------------------

    @Transactional
    public void handleTaskUpdate(UUID taskId, TaskStatusUpdate update) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("Task not found: " + taskId));

        task.setStatus(update.getStatus());
        if (update.getOutputLocation() != null) {
            task.setOutputLocation(update.getOutputLocation());
        }
        if (update.getErrorMessage() != null) {
            task.setErrorMessage(update.getErrorMessage());
        }
        taskRepository.save(task);

        Job job = task.getJob();

        // Ignore callbacks for cancelled/completed/failed jobs
        if (job.getStatus() == JobStatus.CANCELLED
                || job.getStatus() == JobStatus.COMPLETED
                || job.getStatus() == JobStatus.FAILED) {
            log.debug("Ignoring task update for job {} in terminal state {}", job.getJobId(), job.getStatus());
            return;
        }

        switch (update.getStatus()) {
            case COMPLETED -> onTaskCompleted(job, task);
            case FAILED    -> onTaskFailed(job, task);
            default        -> { /* IN_PROGRESS — nothing extra to do */ }
        }
    }

    // ----------------------------------------------------------------
    // Heartbeat from a worker
    // ----------------------------------------------------------------

    @Transactional
    public void recordHeartbeat(UUID taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setLastHeartbeat(java.time.Instant.now());
            taskRepository.save(task);
        });
    }

    // ----------------------------------------------------------------
    // Query job status
    // ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JobStatusResponse> listJobs(String userId) {
        return jobRepository.findByUserId(userId).stream()
                .map(job -> JobStatusResponse.builder()
                        .jobId(job.getJobId())
                        .status(job.getStatus())
                        .createdAt(job.getCreatedAt())
                        .updatedAt(job.getUpdatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public JobStatusResponse getJobStatus(UUID jobId, String userId) {
        Job job = jobRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));

        List<String> outputUrls = null;
        if (job.getStatus() == JobStatus.COMPLETED && job.getOutputPath() != null) {
            outputUrls = minioService.listObjects(job.getOutputPath()).stream()
                    .map(key -> minioService.presignedGetUrl(key, 3600))
                    .toList();
        }

        return JobStatusResponse.builder()
                .jobId(job.getJobId())
                .status(job.getStatus())
                .outputUrls(outputUrls)
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    // ----------------------------------------------------------------
    // Internal orchestration helpers
    // ----------------------------------------------------------------

    private void startMapPhase(Job job) {
        job.setStatus(JobStatus.MAP_PHASE);
        jobRepository.save(job);

        List<String> splits = splitInput(job.getInputPath(), job.getNumMapTasks());

        for (int i = 0; i < splits.size(); i++) {
            Task task = new Task();
            task.setJob(job);
            task.setTaskType(TaskType.MAP);
            task.setStatus(TaskStatus.IDLE);
            task.setInputSplit(splits.get(i));
            task = taskRepository.save(task);

            String podName = k8sLauncher.launchWorker(
                    task.getTaskId(), job.getJobId(),
                    "MAP", task.getInputSplit(), job.getCodePath());

            task.setWorkerPodId(podName);
            task.setStatus(TaskStatus.IN_PROGRESS);
            taskRepository.save(task);
        }
        log.info("Job {} — MAP phase started ({} tasks)", job.getJobId(), splits.size());
    }

    private void startReducePhase(Job job) {
        job.setStatus(JobStatus.REDUCE_PHASE);
        jobRepository.save(job);

        // collect all intermediate output keys from completed map tasks
        List<String> mapOutputKeys = taskRepository
                .findByJobJobIdAndTaskType(job.getJobId(), TaskType.MAP)
                .stream()
                .map(Task::getOutputLocation)
                .filter(loc -> loc != null && !loc.isBlank())
                .toList();

        if (mapOutputKeys.isEmpty()) {
            log.error("Job {} — no map output keys found, failing job", job.getJobId());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("No map output produced");
            jobRepository.save(job);
            return;
        }

        // distribute map outputs evenly across reducers
        int numReducers = job.getNumReduceTasks();
        int chunkSize = Math.max(1, (int) Math.ceil((double) mapOutputKeys.size() / numReducers));

        for (int r = 0; r < numReducers; r++) {
            int from = r * chunkSize;
            if (from >= mapOutputKeys.size()) {
                // No data left for this reducer — skip it
                log.info("Job {} — reducer {} skipped (no input)", job.getJobId(), r);
                continue;
            }
            int to = Math.min(from + chunkSize, mapOutputKeys.size());
            String reduceSplit = String.join(",", mapOutputKeys.subList(from, to));

            Task task = new Task();
            task.setJob(job);
            task.setTaskType(TaskType.REDUCE);
            task.setStatus(TaskStatus.IDLE);
            task.setInputSplit(reduceSplit);
            task = taskRepository.save(task);

            String podName = k8sLauncher.launchWorker(
                    task.getTaskId(), job.getJobId(),
                    "REDUCE", task.getInputSplit(), job.getCodePath());

            task.setWorkerPodId(podName);
            task.setStatus(TaskStatus.IN_PROGRESS);
            taskRepository.save(task);
        }
        log.info("Job {} — REDUCE phase started ({} tasks)", job.getJobId(), job.getNumReduceTasks());
    }

    private void onTaskCompleted(Job job, Task completedTask) {
        UUID jobId = job.getJobId();

        if (completedTask.getTaskType() == TaskType.MAP) {
            long remaining = taskRepository.countByJobJobIdAndStatus(jobId, TaskStatus.IDLE)
                           + taskRepository.countByJobJobIdAndStatus(jobId, TaskStatus.IN_PROGRESS);
            // Only count map tasks that are still pending
            long mapPending = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.MAP).stream()
                    .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                    .count();

            if (mapPending == 0) {
                log.info("Job {} — all MAP tasks done, starting REDUCE phase", jobId);
                startReducePhase(job);
            }
        } else {
            // REDUCE task completed
            long reducePending = taskRepository.findByJobJobIdAndTaskType(jobId, TaskType.REDUCE).stream()
                    .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                    .count();

            if (reducePending == 0) {
                job.setStatus(JobStatus.COMPLETED);
                jobRepository.save(job);
                log.info("Job {} — COMPLETED", jobId);
            }
        }
    }

    private void onTaskFailed(Job job, Task failedTask) {
        if (failedTask.getRetryCount() < 3) {
            failedTask.setRetryCount(failedTask.getRetryCount() + 1);
            failedTask.setStatus(TaskStatus.IDLE);

            // delete the old K8s Job before retrying so there's no name conflict
            String oldPodId = failedTask.getWorkerPodId();
            if (oldPodId != null) {
                try {
                    k8sLauncher.deleteJob(oldPodId);
                } catch (Exception e) {
                    log.warn("Could not delete old K8s Job {}: {}", oldPodId, e.getMessage());
                }
            }
            failedTask.setWorkerPodId(null);
            taskRepository.save(failedTask);

            log.warn("Task {} failed, retrying (attempt {})", failedTask.getTaskId(), failedTask.getRetryCount());

            String podName = k8sLauncher.launchWorker(
                    failedTask.getTaskId(), job.getJobId(),
                    failedTask.getTaskType().name(),
                    failedTask.getInputSplit(), job.getCodePath());

            failedTask.setWorkerPodId(podName);
            failedTask.setStatus(TaskStatus.IN_PROGRESS);
            taskRepository.save(failedTask);
        } else {
            log.error("Task {} permanently failed after {} retries", failedTask.getTaskId(), failedTask.getRetryCount());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Task " + failedTask.getTaskId() + " permanently failed: " + failedTask.getErrorMessage());
            jobRepository.save(job);
        }
    }

    /**
     * Splits an input MinIO prefix into N sub-prefixes.
     * Each map worker will list objects under its assigned sub-prefix.
     */
    private List<String> splitInput(String inputPath, int numSplits) {
        List<String> allObjects = minioService.listObjects(inputPath);
        if (allObjects.isEmpty()) {
            return List.of(inputPath);
        }

        int chunkSize = Math.max(1, (int) Math.ceil((double) allObjects.size() / numSplits));
        List<String> splits = new java.util.ArrayList<>();
        for (int i = 0; i < allObjects.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, allObjects.size());
            // Pass comma-separated object keys as the split descriptor
            splits.add(String.join(",", allObjects.subList(i, end)));
        }
        return splits;
    }
}
