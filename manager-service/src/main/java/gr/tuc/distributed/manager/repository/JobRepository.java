package gr.tuc.distributed.manager.repository;

import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.manager.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByUserId(String userId);

    List<Job> findByStatus(JobStatus status);

    Optional<Job> findByJobIdAndUserId(UUID jobId, String userId);
}
