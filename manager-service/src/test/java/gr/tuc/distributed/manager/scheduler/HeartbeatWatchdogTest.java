package gr.tuc.distributed.manager.scheduler;

import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.Job;
import gr.tuc.distributed.manager.entity.Task;
import gr.tuc.distributed.manager.repository.TaskRepository;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HeartbeatWatchdog}.
 * Verifies that stale tasks are detected and marked FAILED,
 * triggering the retry logic in the orchestration service.
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatWatchdogTest {

    @Mock TaskRepository taskRepository;
    @Mock JobOrchestrationService orchestrationService;

    @InjectMocks HeartbeatWatchdog watchdog;

    @Test
    void detectsDeadWorkerAndMarksTaskFailed() {
        Task dead = buildInProgressTask(Instant.now().minus(60, ChronoUnit.SECONDS));

        when(taskRepository.findByStatusAndLastHeartbeatBefore(eq(TaskStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(dead));

        watchdog.checkDeadWorkers();

        ArgumentCaptor<TaskStatusUpdate> captor = ArgumentCaptor.forClass(TaskStatusUpdate.class);
        verify(orchestrationService).handleTaskUpdate(eq(dead.getTaskId()), captor.capture());

        TaskStatusUpdate update = captor.getValue();
        assertThat(update.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(update.getErrorMessage()).contains("heartbeat timeout");
    }

    @Test
    void doesNotTouchAliveWorkers() {
        // No dead tasks found
        when(taskRepository.findByStatusAndLastHeartbeatBefore(eq(TaskStatus.IN_PROGRESS), any()))
                .thenReturn(List.of());

        watchdog.checkDeadWorkers();

        verify(orchestrationService, never()).handleTaskUpdate(any(), any());
    }

    @Test
    void handlesMultipleDeadWorkers() {
        Task dead1 = buildInProgressTask(Instant.now().minus(45, ChronoUnit.SECONDS));
        Task dead2 = buildInProgressTask(Instant.now().minus(90, ChronoUnit.SECONDS));

        when(taskRepository.findByStatusAndLastHeartbeatBefore(eq(TaskStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(dead1, dead2));

        watchdog.checkDeadWorkers();

        verify(orchestrationService, times(2)).handleTaskUpdate(any(), any());
    }

    // ---- helpers ----

    private Task buildInProgressTask(Instant lastHeartbeat) {
        Job job = new Job();
        try {
            var f = Job.class.getDeclaredField("jobId");
            f.setAccessible(true);
            f.set(job, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        job.setUserId("u1");
        job.setStatus(JobStatus.MAP_PHASE);

        Task task = new Task();
        try {
            var f = Task.class.getDeclaredField("taskId");
            f.setAccessible(true);
            f.set(task, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        task.setJob(job);
        task.setTaskType(TaskType.MAP);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setWorkerPodId("worker-pod-123");
        task.setLastHeartbeat(lastHeartbeat);
        return task;
    }
}
