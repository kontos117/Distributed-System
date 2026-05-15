package gr.tuc.distributed.manager.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Class that starts real PostgreSQL and MinIO containers once per test
 * suite and wires their ports into Spring's property sources.
 *
 * extend this class for any @SpringBootTest that needs infrastructure.
 */
public abstract class TestContainersBase {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("mapreduce_test")
                    .withUsername("test")
                    .withPassword("test");

    @SuppressWarnings("resource")
    public static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
                    .withCommand("server /data")
                    .withEnv("MINIO_ROOT_USER",     "minioadmin")
                    .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                    .withExposedPorts(9000);

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // MinIO
        registry.add("minio.endpoint",   () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("minio.public-endpoint", () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
        registry.add("minio.bucket",     () -> "mapreduce-test");

        //disable Kubernetes client auto-config (no real cluster in tests)
        registry.add("kubernetes.disable.autoConfig", () -> "true");

        //disable Keycloak JWT validation in tests — use a test-only issuer
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                     () -> "http://localhost:9999/realms/test");
    }
}
