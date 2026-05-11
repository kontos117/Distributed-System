package gr.tuc.distributed.ui.controller;

import gr.tuc.distributed.common.dto.FileUploadResponse;
import gr.tuc.distributed.ui.client.ManagerClient;
import gr.tuc.distributed.ui.service.CodeAssistService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/code")
@RequiredArgsConstructor
public class CodeController {

    private final ManagerClient managerClient;
    private final CodeAssistService codeAssistService;

    /**
     * POST /api/v1/code
     * Upload mapper/reducer code (.jar). Returns a codeId.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<FileUploadResponse> uploadCode(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {

        return ResponseEntity.ok(managerClient.uploadCode(file, request.getHeader("Authorization")));
    }

    /**
     * GET /api/v1/code/assist/candidates
     * Discover recently built .jar artifacts in the local workspace.
     */
    @GetMapping("/assist/candidates")
    public ResponseEntity<java.util.List<CodeAssistService.JarCandidate>> discoverCandidates() {
        return ResponseEntity.ok(codeAssistService.discoverCandidates());
    }

    /**
     * POST /api/v1/code/assist/candidates/{candidateId}/upload
     * Upload a discovered local jar directly to manager-service as code.
     */
    @PostMapping("/assist/candidates/{candidateId}/upload")
    public ResponseEntity<FileUploadResponse> uploadDetectedCandidate(
            @PathVariable String candidateId,
            HttpServletRequest request) throws IOException {

        return ResponseEntity.ok(
                codeAssistService.uploadDetectedCandidate(candidateId, request.getHeader("Authorization"))
        );
    }

    /**
     * POST /api/v1/code/assist/build?module=wordcount|loggrep|arraylookup
     * Build an example module using Maven and return execution result.
     */
    @PostMapping("/assist/build")
    public ResponseEntity<CodeAssistService.BuildResult> buildExampleModule(
            @RequestParam(defaultValue = "wordcount") String module) {
        return ResponseEntity.ok(codeAssistService.buildExample(module));
    }
}
