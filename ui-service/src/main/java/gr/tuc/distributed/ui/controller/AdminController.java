package gr.tuc.distributed.ui.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Admin endpoints — only accessible to users with Keycloak role 'admin'.
 * Proxies user management calls to Keycloak Admin REST API.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    @Value("${keycloak.admin.base-url}")
    private String keycloakAdminUrl;

    @Value("${keycloak.realm}")
    private String realm;

    private final RestClient.Builder restClientBuilder;

    /**
     * POST /api/v1/admin/users
     * Create a new user in Keycloak.
     */
    @PostMapping("/users")
    public ResponseEntity<Void> createUser(
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest httpRequest) {

        String authHeader = httpRequest.getHeader("Authorization");
        // Forward to Keycloak Admin API
        restClientBuilder.build()
                .post()
                .uri(keycloakAdminUrl + "/admin/realms/" + realm + "/users")
                .header("Authorization", authHeader)
                .body(Map.of(
                        "username", request.getUsername(),
                        "email",    request.getEmail(),
                        "enabled",  true,
                        "credentials", java.util.List.of(Map.of(
                                "type",      "password",
                                "value",     request.getPassword(),
                                "temporary", false))))
                .retrieve()
                .toBodilessEntity();

        log.info("Admin created user: {}", request.getUsername());
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/v1/admin/users/{userId}
     * Delete a user from Keycloak.
     */
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable("userId") String userId,
            HttpServletRequest httpRequest) {

        String authHeader = httpRequest.getHeader("Authorization");
        restClientBuilder.build()
                .delete()
                .uri(keycloakAdminUrl + "/admin/realms/" + realm + "/users/" + userId)
                .header("Authorization", authHeader)
                .retrieve()
                .toBodilessEntity();

        log.info("Admin deleted user: {}", userId);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String username;
        @NotBlank private String email;
        @NotBlank private String password;
    }
}
