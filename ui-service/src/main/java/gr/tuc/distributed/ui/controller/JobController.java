package gr.tuc.distributed.ui.controller;

import gr.tuc.distributed.common.dto.JobStatusResponse;
import gr.tuc.distributed.common.dto.JobSubmitRequest;
import gr.tuc.distributed.ui.client.ManagerClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final ManagerClient managerClient;

    /**
     * GET /api/v1/jobs
     * List all jobs for the authenticated user.
     */
    @GetMapping
    public ResponseEntity<List<JobStatusResponse>> listJobs(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(managerClient.listJobs(httpRequest.getHeader("Authorization")));
    }

    /**
     * POST /api/v1/jobs
     * Submit a new Map-Reduce job.
     * Body: { "dataId": "...", "codeId": "...", "numReducers": 5 }
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submitJob(
            @Valid @RequestBody JobSubmitRequest request,
            HttpServletRequest httpRequest) {

        String jobId = managerClient.submitJob(request, httpRequest.getHeader("Authorization"));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("jobId", jobId));
    }

    /**
     * GET /api/v1/jobs/{jobId}
     * Poll status; returns output URLs when COMPLETED.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(
            @PathVariable("jobId") UUID jobId,
            HttpServletRequest httpRequest) {

        return ResponseEntity.ok(managerClient.getJobStatus(jobId, httpRequest.getHeader("Authorization")));
    }

    /**
     * DELETE /api/v1/jobs/{jobId}
     * Cancel an in-progress job.
     */
    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> cancelJob(
            @PathVariable("jobId") UUID jobId,
            HttpServletRequest httpRequest) {

        managerClient.cancelJob(jobId, httpRequest.getHeader("Authorization"));
        return ResponseEntity.noContent().build();
    }

}
