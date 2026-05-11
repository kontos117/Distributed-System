package gr.tuc.distributed.manager.controller;

import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

import java.util.UUID;

/**
 * Internal API consumed by Worker pods to report progress.
 */
@RestController
@RequestMapping("/internal/tasks")
@RequiredArgsConstructor
@Slf4j
public class InternalTaskController {

    private final JobOrchestrationService orchestrationService;

    //Worker reports COMPLETED / FAILED / IN_PROGRESS
    @PostMapping("/{taskId}/status")
    public ResponseEntity<Void> updateTaskStatus(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody TaskStatusUpdate update) {

        orchestrationService.handleTaskUpdate(taskId, update);
        return ResponseEntity.noContent().build();
    }

    // Worker sends a heartbeat to prove it is still alive
    @PostMapping("/{taskId}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable("taskId") UUID taskId) {
        orchestrationService.recordHeartbeat(taskId);
        return ResponseEntity.noContent().build();
    }

    // Returns 404 directly, avoids forwarding to /error which is JWT-protected
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> handleNotFound(NoSuchElementException ex) {
        log.warn("Internal task not found: {}", ex.getMessage());
        return ResponseEntity.notFound().build();
    }

    // Catch-all — avoids forwarding to JWT-protected /error on any other exception
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleError(Exception ex) {
        log.error("Internal task error: {}", ex.getMessage());
        return ResponseEntity.internalServerError().build();
    }
}
