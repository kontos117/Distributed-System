package gr.tuc.distributed.ui.client;

import gr.tuc.distributed.common.dto.FileUploadResponse;
import gr.tuc.distributed.common.dto.JobStatusResponse;
import gr.tuc.distributed.common.dto.JobSubmitRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Typed proxy for Manager Service internal APIs.
 * The caller must supply the raw Authorization header value so it is
 * forwarded to the Manager (the Manager validates it independently).
 */
@Component
public class ManagerClient {

    private final RestClient managerClient;

    public ManagerClient(@Qualifier("managerRestClient") RestClient managerClient) {
        this.managerClient = managerClient;
    }

    public FileUploadResponse uploadData(MultipartFile file, String authHeader) throws IOException {
        return postMultipart("/internal/files/data", file, authHeader, FileUploadResponse.class);
    }

    public FileUploadResponse uploadCode(MultipartFile file, String authHeader) throws IOException {
        return postMultipart("/internal/files/code", file, authHeader, FileUploadResponse.class);
    }

    public FileUploadResponse uploadCodeBytes(String filename, byte[] content, String authHeader) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        return managerClient.post()
                .uri("/internal/files/code")
                .header("Authorization", authHeader)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(FileUploadResponse.class);
    }

    public List<JobStatusResponse> listJobs(String authHeader) {
        return managerClient.get()
                .uri("/internal/jobs")
                .header("Authorization", authHeader)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<JobStatusResponse>>() {});
    }

    public String submitJob(JobSubmitRequest request, String authHeader) {
        Map<?, ?> response = managerClient.post()
                .uri("/internal/jobs")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
        return (String) response.get("jobId");
    }

    public JobStatusResponse getJobStatus(UUID jobId, String authHeader) {
        return managerClient.get()
                .uri("/internal/jobs/{jobId}", jobId)
                .header("Authorization", authHeader)
                .retrieve()
                .body(JobStatusResponse.class);
    }

    public void cancelJob(UUID jobId, String authHeader) {
        managerClient.delete()
                .uri("/internal/jobs/{jobId}", jobId)
                .header("Authorization", authHeader)
                .retrieve()
                .toBodilessEntity();
    }

    private <T> T postMultipart(String uri, MultipartFile file,
                                String authHeader, Class<T> responseType) throws IOException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());

        return managerClient.post()
                .uri(uri)
                .header("Authorization", authHeader)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(responseType);
    }
}
