package gr.tuc.distributed.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.tuc.distributed.common.dto.JobStatusResponse;
import gr.tuc.distributed.common.dto.JobSubmitRequest;
import gr.tuc.distributed.common.enums.JobStatus;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc tests for the internal job endpoints.
 * Security is disabled via TestSecurityConfig to simplify JWT handling.
 */
@WebMvcTest(InternalJobController.class)
@Import(gr.tuc.distributed.manager.integration.TestSecurityConfig.class)
class InternalJobControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean JobOrchestrationService orchestrationService;

    @Test
    void submitJob_validRequest_returns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(orchestrationService.submitJob(any(JobSubmitRequest.class), anyString()))
                .thenReturn(jobId);

        JobSubmitRequest request = new JobSubmitRequest();
        request.setDataId(UUID.randomUUID().toString());
        request.setCodeId(UUID.randomUUID().toString());
        request.setNumReducers(2);

        mockMvc.perform(post("/internal/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void getJobStatus_existingJob_returns200() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobStatusResponse response = JobStatusResponse.builder()
                .jobId(jobId)
                .status(JobStatus.MAP_PHASE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orchestrationService.getJobStatus(eq(jobId), anyString()))
                .thenReturn(response);

        mockMvc.perform(get("/internal/jobs/{jobId}", jobId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAP_PHASE"));
    }

    @Test
    void getJobStatus_nonExistent_returns404() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(orchestrationService.getJobStatus(eq(jobId), anyString()))
                .thenThrow(new NoSuchElementException("Job not found: " + jobId));

        mockMvc.perform(get("/internal/jobs/{jobId}", jobId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("user-1"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelJob_nonExistent_returns404() throws Exception {
        UUID jobId = UUID.randomUUID();
        doThrow(new NoSuchElementException("Job not found"))
                .when(orchestrationService).cancelJob(eq(jobId), anyString());

        mockMvc.perform(delete("/internal/jobs/{jobId}", jobId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("user-1"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelJob_alreadyCompleted_returns409() throws Exception {
        UUID jobId = UUID.randomUUID();
        doThrow(new IllegalStateException("Job is already in terminal state: COMPLETED"))
                .when(orchestrationService).cancelJob(eq(jobId), anyString());

        mockMvc.perform(delete("/internal/jobs/{jobId}", jobId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("user-1"))))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelJob_success_returns204() throws Exception {
        UUID jobId = UUID.randomUUID();

        mockMvc.perform(delete("/internal/jobs/{jobId}", jobId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("user-1"))))
                .andExpect(status().isNoContent());

        verify(orchestrationService).cancelJob(eq(jobId), anyString());
    }

    @Test
    void listJobs_returnsListOfJobs() throws Exception {
        List<JobStatusResponse> jobs = List.of(
                JobStatusResponse.builder()
                        .jobId(UUID.randomUUID())
                        .status(JobStatus.COMPLETED)
                        .createdAt(Instant.now())
                        .build()
        );
        when(orchestrationService.listJobs(anyString())).thenReturn(jobs);

        mockMvc.perform(get("/internal/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }
}
