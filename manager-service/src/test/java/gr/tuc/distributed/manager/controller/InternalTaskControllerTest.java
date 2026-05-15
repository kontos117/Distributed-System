package gr.tuc.distributed.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.tuc.distributed.common.dto.TaskStatusUpdate;
import gr.tuc.distributed.common.enums.TaskStatus;
import gr.tuc.distributed.manager.service.JobOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the worker callback endpoints.
 * These endpoints are open (no JWT required) per the security config.
 */
@WebMvcTest(InternalTaskController.class)
@Import(gr.tuc.distributed.manager.integration.TestSecurityConfig.class)
class InternalTaskControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean JobOrchestrationService orchestrationService;

    @Test
    void updateTaskStatus_validUpdate_returns204() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskStatusUpdate update = new TaskStatusUpdate();
        update.setStatus(TaskStatus.COMPLETED);
        update.setOutputLocation("some/output/path");

        mockMvc.perform(post("/internal/tasks/{taskId}/status", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNoContent());

        verify(orchestrationService).handleTaskUpdate(eq(taskId), any(TaskStatusUpdate.class));
    }

    @Test
    void updateTaskStatus_unknownTask_returns404() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskStatusUpdate update = new TaskStatusUpdate();
        update.setStatus(TaskStatus.COMPLETED);

        doThrow(new NoSuchElementException("Task not found"))
                .when(orchestrationService).handleTaskUpdate(eq(taskId), any());

        mockMvc.perform(post("/internal/tasks/{taskId}/status", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());
    }

    @Test
    void heartbeat_returns204() throws Exception {
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/internal/tasks/{taskId}/heartbeat", taskId))
                .andExpect(status().isNoContent());

        verify(orchestrationService).recordHeartbeat(eq(taskId));
    }

    @Test
    void updateTaskStatus_failedStatus_returns204() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskStatusUpdate update = new TaskStatusUpdate();
        update.setStatus(TaskStatus.FAILED);
        update.setErrorMessage("OOM killed");

        mockMvc.perform(post("/internal/tasks/{taskId}/status", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNoContent());

        verify(orchestrationService).handleTaskUpdate(eq(taskId), any(TaskStatusUpdate.class));
    }

    @Test
    void updateTaskStatus_internalError_returns500() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskStatusUpdate update = new TaskStatusUpdate();
        update.setStatus(TaskStatus.COMPLETED);

        doThrow(new RuntimeException("DB connection failed"))
                .when(orchestrationService).handleTaskUpdate(eq(taskId), any());

        mockMvc.perform(post("/internal/tasks/{taskId}/status", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isInternalServerError());
    }
}
