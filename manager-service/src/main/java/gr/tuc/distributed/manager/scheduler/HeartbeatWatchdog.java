package gr.tuc.distributed.manager.scheduler;

import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.manager.repository.TaskRepository;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically scans for IN_PROGRESS tasks that have stopped sending heartbeats.
 * If a task's last heartbeat is older than TIMEOUT_SECONDS it is marked FAILED,
 * which triggers the retry logic in JobOrchestrationService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HeartbeatWatchdog {

    private static final long TIMEOUT_SECONDS = 30;

    private final TaskRepository taskRepository;
    private final JobOrchestrationService orchestrationService;

    @Scheduled(fixedDelayString = "${mapreduce.watchdog.interval-ms:15000}")
    public void checkDeadWorkers() {
        Instant threshold = Instant.now().minus(TIMEOUT_SECONDS, ChronoUnit.SECONDS);

        var deadTasks = taskRepository
                .findByStatusAndLastHeartbeatBefore(TaskStatus.IN_PROGRESS, threshold);

        if (!deadTasks.isEmpty()) {
            log.warn("Watchdog: {} dead worker(s) detected", deadTasks.size());
        }

        deadTasks.forEach(task -> {
            log.warn("Task {} (worker {}) missed heartbeat — marking FAILED for retry",
                    task.getTaskId(), task.getWorkerPodId());

            TaskStatusUpdate failUpdate = new TaskStatusUpdate();
            failUpdate.setStatus(TaskStatus.FAILED);
            failUpdate.setErrorMessage("Worker heartbeat timeout after " + TIMEOUT_SECONDS + "s");

            orchestrationService.handleTaskUpdate(task.getTaskId(), failUpdate);
        });
    }
}
