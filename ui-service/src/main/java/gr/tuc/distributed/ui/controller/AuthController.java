package gr.tuc.distributed.ui.controller;

import gr.tuc.distributed.ui.client.KeycloakAdminClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final KeycloakAdminClient keycloakAdmin;

    @Value("${keycloak.auth.token-uri}")
    private String tokenUri;

    @Value("${keycloak.auth.client-id}")
    private String clientId;

    // ── Login ──────────────────────────────────────────────────────────

    @PostMapping("/token")
    public ResponseEntity<?> login(@RequestBody LoginRequest body) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", body.getUsername());
        form.add("password", body.getPassword());

        try {
            Map<?, ?> response = RestClient.create().post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            return ResponseEntity.ok(Map.of(
                    "access_token", response.get("access_token"),
                    "expires_in",   response.get("expires_in")
            ));
        } catch (RestClientResponseException e) {
            log.warn("Login failed for user '{}': {}", body.getUsername(), e.getStatusCode());
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", "Invalid username or password"));
        }
    }

    // ── Username availability ──────────────────────────────────────────

    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Boolean>> checkUsername(@RequestParam String username) {
        boolean available = keycloakAdmin.isUsernameAvailable(username.trim().toLowerCase());
        return ResponseEntity.ok(Map.of("available", available));
    }

    // ── Register ───────────────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest body) {
        String username = body.getUsername() == null ? "" : body.getUsername().trim().toLowerCase();
        String password = body.getPassword() == null ? "" : body.getPassword();

        if (username.length() < 3) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username must be at least 3 characters"));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Password must be at least 6 characters"));
        }

        try {
            keycloakAdmin.createUser(username, password);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Account created"));
        } catch (KeycloakAdminClient.UsernameConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username is already taken"));
        } catch (KeycloakAdminClient.RegistrationException e) {
            log.error("Registration error: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── DTOs ───────────────────────────────────────────────────────────

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    public static class RegisterRequest {
        private String username;
        private String password;
    }
}
