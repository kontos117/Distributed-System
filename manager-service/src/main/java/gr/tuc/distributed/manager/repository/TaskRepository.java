package gr.tuc.distributed.manager.repository;

import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByJobJobId(UUID jobId);

    List<Task> findByJobJobIdAndTaskType(UUID jobId, TaskType type);

    List<Task> findByJobJobIdAndStatus(UUID jobId, TaskStatus status);

    /** Used by the heartbeat watchdog to detect dead workers. */
    List<Task> findByStatusAndLastHeartbeatBefore(TaskStatus status, Instant threshold);

    long countByJobJobIdAndStatus(UUID jobId, TaskStatus status);

    long countByJobJobIdAndTaskType(UUID jobId, TaskType type);
}
