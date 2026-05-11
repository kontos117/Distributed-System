package gr.tuc.distributed.ui.controller;

import gr.tuc.distributed.common.dto.FileUploadResponse;
import gr.tuc.distributed.ui.client.ManagerClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataController {

    private final ManagerClient managerClient;

    /**
     * POST /api/v1/data
     * Upload an input data file. Returns a dataId for use in job submission.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<FileUploadResponse> uploadData(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {

        return ResponseEntity.ok(managerClient.uploadData(file, request.getHeader("Authorization")));
    }
}
