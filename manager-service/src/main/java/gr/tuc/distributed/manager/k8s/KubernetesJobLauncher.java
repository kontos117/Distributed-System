package gr.tuc.distributed.manager.k8s;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamically spawns Kubernetes Jobs for Map and Reduce workers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KubernetesJobLauncher {

    private final KubernetesClient kubernetesClient;

    @Value("${k8s.namespace:mapreduce}")
    private String namespace;

    @Value("${k8s.worker.image}")
    private String workerImage;

    @Value("${k8s.worker.backoff-limit:3}")
    private int backoffLimit;

    @Value("${app.manager.internal-url}")
    private String managerUrl;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    @Value("${minio.access-key}")
    private String minioAccessKey;

    @Value("${minio.secret-key}")
    private String minioSecretKey;

    @Value("${minio.bucket}")
    private String minioBucket;

    /**
     * Launches a Kubernetes Job for a single worker task.
     *
     * @param taskId     UUID of the task (worker reports back using this)
     * @param jobId      UUID of the parent Map-Reduce job
     * @param taskType   "MAP" or "REDUCE"
     * @param inputSplit MinIO path/prefix this worker should read
     * @param codePath   MinIO path of the uploaded .jar
     * @return the name of the created Kubernetes Job
     */
    public String launchWorker(UUID taskId, UUID jobId, String taskType,
                               String inputSplit, String codePath) {
        String k8sJobName = "worker-" + taskType.toLowerCase() + "-" + taskId;

        Job k8sJob = new JobBuilder()
                .withNewMetadata()
                    .withName(k8sJobName)
                    .withNamespace(namespace)
                    .withLabels(Map.of(
                            "app", "mapreduce-worker",
                            "job-id", jobId.toString(),
                            "task-id", taskId.toString(),
                            "task-type", taskType.toLowerCase()))
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(backoffLimit)
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(Map.of("app", "mapreduce-worker"))
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withContainers(buildWorkerContainer(
                                    taskId, jobId, taskType, inputSplit, codePath))
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        kubernetesClient.batch().v1().jobs()
                .inNamespace(namespace)
                .resource(k8sJob)
                .create();

        log.info("Launched K8s Job {} for task {} ({})", k8sJobName, taskId, taskType);
        return k8sJobName;
    }

    /** Delete a Kubernetes Job and its pods (called on cleanup or cancellation). */
    public void deleteJob(String k8sJobName) {
        kubernetesClient.batch().v1().jobs()
                .inNamespace(namespace)
                .withName(k8sJobName)
                .delete();
        log.info("Deleted K8s Job {}", k8sJobName);
    }

    private Container buildWorkerContainer(UUID taskId, UUID jobId, String taskType,
                                           String inputSplit, String codePath) {
        return new ContainerBuilder()
                .withName("worker")
                .withImage(workerImage)
                .withImagePullPolicy("IfNotPresent")
                .withEnv(List.of(
                        envVar("TASK_ID",         taskId.toString()),
                        envVar("JOB_ID",          jobId.toString()),
                        envVar("TASK_TYPE",       taskType),
                        envVar("INPUT_SPLIT",     inputSplit),
                        envVar("CODE_PATH",       codePath),
                        envVar("MANAGER_URL",     managerUrl),
                        envVar("MINIO_ENDPOINT",  minioEndpoint),
                        envVar("MINIO_ACCESS_KEY", minioAccessKey),
                        envVar("MINIO_SECRET_KEY", minioSecretKey),
                        envVar("MINIO_BUCKET",    minioBucket)))
                .withResources(new ResourceRequirementsBuilder()
                        .withRequests(Map.of(
                                "cpu",    new Quantity("250m"),
                                "memory", new Quantity("256Mi")))
                        .withLimits(Map.of(
                                "cpu",    new Quantity("1"),
                                "memory", new Quantity("512Mi")))
                        .build())
                .build();
    }

    private EnvVar envVar(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }
}
