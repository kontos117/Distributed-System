package gr.tuc.distributed.manager.integration;

import gr.tuc.distributed.manager.minio.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link MinioStorageService} against a real MinIO container.
 * Validates upload, download, list, and presigned-URL operations.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class MinioStorageServiceIT extends TestContainersBase {

    @Autowired MinioStorageService minioService;

    @Test
    void uploadAndDownload_contentMatches() throws Exception {
        String key = "test/integration/" + UUID.randomUUID() + "/data.txt";
        String content = "Hello, MinIO integration test!";

        minioService.upload(key,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                content.length(), "text/plain");

        try (InputStream is = minioService.download(key)) {
            String downloaded = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(downloaded).isEqualTo(content);
        }
    }

    @Test
    void uploadAndList_objectKeyAppears() {
        String prefix = "test/list/" + UUID.randomUUID() + "/";
        String key = prefix + "file1.txt";

        minioService.upload(key,
                new ByteArrayInputStream("data".getBytes()),
                4, "text/plain");

        List<String> keys = minioService.listObjects(prefix);
        assertThat(keys).contains(key);
    }

    @Test
    void uploadAndPresignedUrl_returnsValidUrl() {
        String key = "test/presigned/" + UUID.randomUUID() + "/file.txt";

        minioService.upload(key,
                new ByteArrayInputStream("presigned-test".getBytes()),
                14, "text/plain");

        String url = minioService.presignedGetUrl(key, 3600);
        assertThat(url).isNotNull();
        assertThat(url).contains(key);
    }

    @Test
    void listObjects_nonExistentPrefixReturnsEmpty() {
        List<String> keys = minioService.listObjects("non-existent-prefix/" + UUID.randomUUID());
        assertThat(keys).isEmpty();
    }

    @Test
    void uploadMultipleFiles_listReturnsAll() {
        String prefix = "test/multi/" + UUID.randomUUID() + "/";

        for (int i = 0; i < 3; i++) {
            minioService.upload(prefix + "part-" + i + ".txt",
                    new ByteArrayInputStream(("content-" + i).getBytes()),
                    ("content-" + i).length(), "text/plain");
        }

        List<String> keys = minioService.listObjects(prefix);
        assertThat(keys).hasSize(3);
    }
}
