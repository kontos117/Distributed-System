package gr.tuc.distributed.ui.service;

import gr.tuc.distributed.common.dto.FileUploadResponse;
import gr.tuc.distributed.ui.client.ManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CodeAssistService {

    private final ManagerClient managerClient;

    public record JarCandidate(
            String candidateId,
            String fileName,
            String relativePath,
            long sizeBytes,
            long lastModifiedEpochMs
    ) {}

    public record BuildResult(
            boolean success,
            int exitCode,
            String command,
            String outputTail,
            long finishedAtEpochMs
    ) {}

    public List<JarCandidate> discoverCandidates() {
        Path root = workspaceRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(root, 7)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(this::isCandidateJar)
                    .map(p -> toCandidate(root, p))
                    .sorted(Comparator.comparingLong(JarCandidate::lastModifiedEpochMs).reversed())
                    .limit(30)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public FileUploadResponse uploadDetectedCandidate(String candidateId, String authHeader) throws IOException {
        Path root = workspaceRoot();
        Path file = decodeCandidatePath(candidateId);
        Path normalized = file.normalize();

        if (!normalized.startsWith(root) || !Files.isRegularFile(normalized) || !isCandidateJar(normalized)) {
            throw new IllegalArgumentException("Invalid jar candidate");
        }

        byte[] bytes = Files.readAllBytes(normalized);
        return managerClient.uploadCodeBytes(normalized.getFileName().toString(), bytes, authHeader);
    }

    public BuildResult buildExample(String module) {
        String modulePath = switch (module.toLowerCase(Locale.ROOT)) {
            case "wordcount" -> "examples/wordcount";
            case "loggrep" -> "examples/loggrep";
            case "arraylookup" -> "examples/arraylookup";
            default -> throw new IllegalArgumentException("Unsupported module: " + module);
        };

        Path root = workspaceRoot();
        List<String> command = List.of("mvn", "-pl", modulePath, "-am", "-DskipTests", "package");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);

        Deque<String> tail = new ArrayDeque<>();
        int exit = -1;
        boolean success = false;

        try {
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.addLast(line);
                    if (tail.size() > 120) {
                        tail.removeFirst();
                    }
                }
            }

            boolean finished = p.waitFor(8, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                tail.addLast("Build timed out after 8 minutes.");
            } else {
                exit = p.exitValue();
                success = exit == 0;
            }
        } catch (Exception e) {
            tail.addLast("Build command failed to execute: " + e.getMessage());
        }

        String outputTail = String.join("\n", tail);
        return new BuildResult(success, exit, String.join(" ", command), outputTail, Instant.now().toEpochMilli());
    }

    private Path workspaceRoot() {
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private JarCandidate toCandidate(Path root, Path file) {
        long size = safeSize(file);
        long modified = safeModified(file);
        Path relative = root.relativize(file);
        return new JarCandidate(
                encodeCandidatePath(file),
                file.getFileName().toString(),
                relative.toString().replace('\\', '/'),
                size,
                modified
        );
    }

    private boolean isCandidateJar(Path file) {
        String p = file.toString().replace('\\', '/');
        if (!p.endsWith(".jar") || p.endsWith(".jar.original")) {
            return false;
        }
        if (!p.contains("/target/")) {
            return false;
        }
        if (p.contains("/ui-service/target/ui-service-")) {
            return false;
        }
        return true;
    }

    private String encodeCandidatePath(Path file) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private Path decodeCandidatePath(String candidateId) {
        byte[] decoded = Base64.getUrlDecoder().decode(candidateId);
        String path = new String(decoded, StandardCharsets.UTF_8);
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private long safeModified(Path file) {
        try {
            FileTime t = Files.getLastModifiedTime(file);
            return t.toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
