package gr.tuc.distributed.manager.controller;

import gr.tuc.distributed.common.dto.FileUploadResponse;
import gr.tuc.distributed.manager.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class InternalFileController {

    private final FileService fileService;

    @PostMapping("/data")
    public ResponseEntity<FileUploadResponse> uploadData(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(fileService.uploadData(file, jwt.getSubject()));
    }

    @PostMapping("/code")
    public ResponseEntity<FileUploadResponse> uploadCode(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(fileService.uploadCode(file, jwt.getSubject()));
    }
}
