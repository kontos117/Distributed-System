package gr.tuc.distributed.manager.repository;

import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.common.enums.TaskType;
import gr.tuc.distributed.manager.entity.Job;
import gr.tuc.distributed.manager.entity.Task;
import gr.tuc.distributed.manager.integration.TestContainersBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DirtiesContext
class TaskRepositoryIT extends TestContainersBase {

    @Autowired TaskRepository taskRepository;
    @Autowired JobRepository  jobRepository;

    private Job job;

    @BeforeEach
    void setUp() {
        job = new Job();
        job.setUserId("u1");
        job.setStatus(JobStatus.MAP_PHASE);
        job.setNumMapTasks(2);
        job.setNumReduceTasks(1);
        job = jobRepository.save(job);
    }

    @Test
    void findByJobJobIdAndTaskTypeReturnsOnlyMatchingType() {
        Task map    = makeTask(TaskType.MAP,    TaskStatus.IN_PROGRESS);
        Task reduce = makeTask(TaskType.REDUCE, TaskStatus.IDLE);
        taskRepository.saveAll(List.of(map, reduce));

        List<Task> mapTasks = taskRepository.findByJobJobIdAndTaskType(job.getJobId(), TaskType.MAP);
        assertThat(mapTasks).hasSize(1);
        assertThat(mapTasks.get(0).getTaskType()).isEqualTo(TaskType.MAP);
    }

    @Test
    void findDeadWorkers_returnsTasksWithStaleHeartbeat() {
        Task alive = makeTask(TaskType.MAP, TaskStatus.IN_PROGRESS);
        alive.setLastHeartbeat(Instant.now());

        Task dead = makeTask(TaskType.MAP, TaskStatus.IN_PROGRESS);
        dead.setLastHeartbeat(Instant.now().minus(60, ChronoUnit.SECONDS));

        taskRepository.saveAll(List.of(alive, dead));

        Instant threshold = Instant.now().minus(30, ChronoUnit.SECONDS);
        List<Task> deadTasks = taskRepository.findByStatusAndLastHeartbeatBefore(
                TaskStatus.IN_PROGRESS, threshold);

        assertThat(deadTasks).hasSize(1);
        assertThat(deadTasks.get(0).getTaskId()).isEqualTo(dead.getTaskId());
    }

    @Test
    void countByStatusReturnsCorrectCount() {
        taskRepository.save(makeTask(TaskType.MAP, TaskStatus.COMPLETED));
        taskRepository.save(makeTask(TaskType.MAP, TaskStatus.COMPLETED));
        taskRepository.save(makeTask(TaskType.MAP, TaskStatus.IN_PROGRESS));

        long completed = taskRepository.countByJobJobIdAndStatus(job.getJobId(), TaskStatus.COMPLETED);
        assertThat(completed).isEqualTo(2);
    }

    // ---------------------------------------------------------------

    private Task makeTask(TaskType type, TaskStatus status) {
        Task t = new Task();
        t.setJob(job);
        t.setTaskType(type);
        t.setStatus(status);
        t.setInputSplit("some/path");
        return t;
    }
}
