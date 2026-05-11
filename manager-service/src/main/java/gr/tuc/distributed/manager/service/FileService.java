package gr.tuc.distributed.manager.service;

import gr.tuc.distributed.common.dto.FileUploadResponse;
import gr.tuc.distributed.manager.entity.FileMetadata;
import gr.tuc.distributed.manager.minio.MinioStorageService;
import gr.tuc.distributed.manager.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final MinioStorageService minioService;
    private final FileMetadataRepository fileMetadataRepository;

    @Transactional
    public FileUploadResponse uploadData(MultipartFile file, String userId) {
        return upload(file, userId, "DATA", "users/" + userId + "/raw/");
    }

    @Transactional
    public FileUploadResponse uploadCode(MultipartFile file, String userId) {
        return upload(file, userId, "CODE", "users/" + userId + "/code/");
    }

    private FileUploadResponse upload(MultipartFile file, String userId,
                                      String fileType, String prefix) {
        String objectKey = prefix + UUID.randomUUID() + "_" + file.getOriginalFilename();
        try {
            minioService.upload(objectKey, file.getInputStream(),
                    file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read upload stream", e);
        }

        FileMetadata meta = new FileMetadata();
        meta.setUserId(userId);
        meta.setFileType(fileType);
        meta.setOriginalName(file.getOriginalFilename());
        meta.setStoragePath(objectKey);
        meta.setSizeBytes(file.getSize());
        meta = fileMetadataRepository.save(meta);

        log.info("User {} uploaded {} file -> {}", userId, fileType, objectKey);
        return FileUploadResponse.builder()
                .id(meta.getFileId().toString())
                .storagePath(objectKey)
                .build();
    }
}
