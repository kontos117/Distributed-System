package gr.tuc.distributed.manager.service;

import gr.tuc.distributed.common.dto.FileUploadResponse;
import gr.tuc.distributed.manager.entity.FileMetadata;
import gr.tuc.distributed.manager.minio.MinioOperationException;
import gr.tuc.distributed.manager.minio.MinioStorageService;
import gr.tuc.distributed.manager.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FileService}.
 * Verifies correct path construction, metadata persistence, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock MinioStorageService minioService;
    @Mock FileMetadataRepository fileMetadataRepository;

    @InjectMocks FileService fileService;

    private final String userId = "user-42";
    private MockMultipartFile sampleFile;

    @BeforeEach
    void setUp() {
        sampleFile = new MockMultipartFile(
                "file", "input.txt", "text/plain", "hello world".getBytes());
    }

    // ---- uploadData ----

    @Test
    void uploadData_storesUnderRawPrefix() {
        stubMetadataSave();
        when(minioService.upload(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        fileService.uploadData(sampleFile, userId);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minioService).upload(keyCaptor.capture(), any(), eq(11L), eq("text/plain"));

        assertThat(keyCaptor.getValue()).startsWith("users/" + userId + "/raw/");
        assertThat(keyCaptor.getValue()).contains("input.txt");
    }

    @Test
    void uploadData_persistsFileMetadataWithCorrectType() {
        stubMetadataSave();
        when(minioService.upload(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        fileService.uploadData(sampleFile, userId);

        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileMetadataRepository).save(captor.capture());

        FileMetadata saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getFileType()).isEqualTo("DATA");
        assertThat(saved.getOriginalName()).isEqualTo("input.txt");
        assertThat(saved.getSizeBytes()).isEqualTo(11L);
        assertThat(saved.getStoragePath()).startsWith("users/" + userId + "/raw/");
    }

    @Test
    void uploadData_returnsResponseWithIdAndPath() {
        stubMetadataSave();
        when(minioService.upload(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        FileUploadResponse response = fileService.uploadData(sampleFile, userId);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getStoragePath()).startsWith("users/" + userId + "/raw/");
    }

    // ---- uploadCode ----

    @Test
    void uploadCode_storesUnderCodePrefix() {
        stubMetadataSave();
        when(minioService.upload(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "wordcount.jar", "application/java-archive", new byte[100]);
        fileService.uploadCode(jarFile, userId);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minioService).upload(keyCaptor.capture(), any(), eq(100L), eq("application/java-archive"));

        assertThat(keyCaptor.getValue()).startsWith("users/" + userId + "/code/");
        assertThat(keyCaptor.getValue()).contains("wordcount.jar");
    }

    @Test
    void uploadCode_persistsFileMetadataWithCodeType() {
        stubMetadataSave();
        when(minioService.upload(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile jarFile = new MockMultipartFile(
                "file", "wordcount.jar", "application/java-archive", new byte[50]);
        fileService.uploadCode(jarFile, userId);

        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileMetadataRepository).save(captor.capture());
        assertThat(captor.getValue().getFileType()).isEqualTo("CODE");
    }

    // ---- error handling ----

    @Test
    void uploadData_throwsWhenMinioFails() {
        when(minioService.upload(anyString(), any(), anyLong(), anyString()))
                .thenThrow(new MinioOperationException("connection refused", new RuntimeException()));

        assertThatThrownBy(() -> fileService.uploadData(sampleFile, userId))
                .isInstanceOf(MinioOperationException.class);

        verify(fileMetadataRepository, never()).save(any());
    }

    // ---- helpers ----

    private void stubMetadataSave() {
        when(fileMetadataRepository.save(any(FileMetadata.class))).thenAnswer(inv -> {
            FileMetadata fm = inv.getArgument(0);
            if (fm.getFileId() == null) {
                try {
                    var f = FileMetadata.class.getDeclaredField("fileId");
                    f.setAccessible(true);
                    f.set(fm, UUID.randomUUID());
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            return fm;
        });
    }
}
