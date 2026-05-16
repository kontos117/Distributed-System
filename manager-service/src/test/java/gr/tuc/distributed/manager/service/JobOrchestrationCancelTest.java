package gr.tuc.distributed.manager.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the cancel-job logic in {@link JobOrchestrationService}.
 */
@ExtendWith(MockitoExtension.class)
class JobOrchestrationCancelTest {

    @Mock JobRepository           jobRepository;
    @Mock TaskRepository          taskRepository;
    @Mock FileMetadataRepository  fileMetadataRepository;
    @Mock KubernetesJobLauncher   k8sLauncher;
    @Mock MinioStorageService     minioService;

    @InjectMocks
    JobOrchestrationService service;

    private final String userId = "user-cancel";

    @Test
    void cancelMapPhaseJob_setsStatusToCancelled() {
        Job job = buildJob(JobStatus.MAP_PHASE);
        Task runningTask = buildTask(job, "worker-map-abc");

        when(jobRepository.findByJobIdAndUserId(job.getJobId(), userId))
                .thenReturn(Optional.of(job));
        when(taskRepository.findByJobJobId(job.getJobId()))
                .thenReturn(List.of(runningTask));
        when(jobRepository.save(any(Job.class))).thenAnswer(i -> i.getArgument(0));

        service.cancelJob(job.getJobId(), userId);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void cancelJob_deletesKubernetesJobs() {
        Job job = buildJob(JobStatus.REDUCE_PHASE);
        Task t1 = buildTask(job, "worker-reduce-1");
        Task t2 = buildTask(job, "worker-reduce-2");

        when(jobRepository.findByJobIdAndUserId(job.getJobId(), userId))
                .thenReturn(Optional.of(job));
        when(taskRepository.findByJobJobId(job.getJobId()))
                .thenReturn(List.of(t1, t2));
        when(jobRepository.save(any(Job.class))).thenAnswer(i -> i.getArgument(0));

        service.cancelJob(job.getJobId(), userId);

        verify(k8sLauncher).deleteJob("worker-reduce-1");
        verify(k8sLauncher).deleteJob("worker-reduce-2");
    }

    @Test
    void cancelCompletedJob_throwsIllegalState() {
        Job job = buildJob(JobStatus.COMPLETED);

        when(jobRepository.findByJobIdAndUserId(job.getJobId(), userId))
                .thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancelJob(job.getJobId(), userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    void cancelFailedJob_throwsIllegalState() {
        Job job = buildJob(JobStatus.FAILED);

        when(jobRepository.findByJobIdAndUserId(job.getJobId(), userId))
                .thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancelJob(job.getJobId(), userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelAlreadyCancelledJob_throwsIllegalState() {
        Job job = buildJob(JobStatus.CANCELLED);

        when(jobRepository.findByJobIdAndUserId(job.getJobId(), userId))
                .thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancelJob(job.getJobId(), userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelNonExistentJob_throwsNoSuchElement() {
        UUID unknownId = UUID.randomUUID();
        when(jobRepository.findByJobIdAndUserId(unknownId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelJob(unknownId, userId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void cancelJob_handlesK8sDeleteFailureGracefully() {
        Job job = buildJob(JobStatus.MAP_PHASE);
        Task task = buildTask(job, "worker-map-broken");

        when(jobRepository.findByJobIdAndUserId(job.getJobId(), userId))
                .thenReturn(Optional.of(job));
        when(taskRepository.findByJobJobId(job.getJobId()))
                .thenReturn(List.of(task));
        doThrow(new RuntimeException("K8s unreachable"))
                .when(k8sLauncher).deleteJob("worker-map-broken");
        when(jobRepository.save(any(Job.class))).thenAnswer(i -> i.getArgument(0));

        // Should not throw — K8s delete failures are logged and swallowed
        service.cancelJob(job.getJobId(), userId);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    // ---- helpers ----

    private Job buildJob(JobStatus status) {
        Job job = new Job();
        try {
            var f = Job.class.getDeclaredField("jobId");
            f.setAccessible(true);
            f.set(job, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        job.setUserId(userId);
        job.setStatus(status);
        job.setNumMapTasks(2);
        job.setNumReduceTasks(1);
        return job;
    }

    private Task buildTask(Job job, String workerPodId) {
        Task task = new Task();
        try {
            var f = Task.class.getDeclaredField("taskId");
            f.setAccessible(true);
            f.set(task, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        task.setJob(job);
        task.setTaskType(TaskType.MAP);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setWorkerPodId(workerPodId);
        return task;
    }
}
