package gr.tuc.distributed.manager.controller;

import gr.tuc.distributed.common.dto.JobStatusResponse;
import gr.tuc.distributed.common.dto.JobSubmitRequest;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Internal API consumed by the UI Service.
 * Not exposed outside the cluster.
 */
@RestController
@RequestMapping("/internal/jobs")
@RequiredArgsConstructor
@Slf4j
public class InternalJobController {

    private final JobOrchestrationService orchestrationService;

    // Called by UI Service to list all jobs for the authenticated user
    @GetMapping
    public ResponseEntity<List<JobStatusResponse>> listJobs(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(orchestrationService.listJobs(jwt.getSubject()));
    }

    // Called by UI Service to submit a new job. Returns jobId immediately
    @PostMapping
    public ResponseEntity<Map<String, String>> submitJob(
            @Valid @RequestBody JobSubmitRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String userId = jwt.getSubject();
        UUID jobId = orchestrationService.submitJob(request, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("jobId", jobId.toString()));
    }

    // Called by UI Service to poll job status
    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(
            @PathVariable("jobId") UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {

        String userId = jwt.getSubject();
        return ResponseEntity.ok(orchestrationService.getJobStatus(jobId, userId));
    }

    // called by UI Service to cancel an in-progress job
    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> cancelJob(
            @PathVariable("jobId") UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {

        orchestrationService.cancelJob(jobId, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        log.warn("Job not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadState(IllegalStateException ex) {
        log.warn("Invalid job state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
