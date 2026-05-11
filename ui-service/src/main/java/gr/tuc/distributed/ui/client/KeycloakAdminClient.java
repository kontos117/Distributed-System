package gr.tuc.distributed.ui.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Keycloak Admin REST API.
 * Obtains a fresh admin token for every operation — admin tokens are
 * short-lived and not worth caching in a stateless service.
 */
@Component
@Slf4j
public class KeycloakAdminClient {

    @Value("${keycloak.admin.base-url}")
    private String baseUrl;

    @Value("${keycloak.admin.username}")
    private String adminUsername;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    @Value("${keycloak.realm}")
    private String realm;

    private final RestClient http = RestClient.create();

    // ── Admin token ────────────────────────────────────────────────────

    private String adminToken() {
        String uri = baseUrl + "/realms/master/protocol/openid-connect/token";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id",  "admin-cli");
        form.add("username",   adminUsername);
        form.add("password",   adminPassword);

        Map<?, ?> resp = http.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        return (String) resp.get("access_token");
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Returns true if no user with this exact username exists in the realm.
     */
    public boolean isUsernameAvailable(String username) {
        String token = adminToken();
        List<?> users = http.get()
                .uri(baseUrl + "/admin/realms/" + realm + "/users?username={u}&exact=true", username)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<?>>() {});
        return users == null || users.isEmpty();
    }

    /**
     * Creates a new enabled user with the given username/password.
     * Throws {@link UsernameConflictException} if the username is already taken,
     * or {@link RegistrationException} on any other error.
     */
    public void createUser(String username, String password) {
        String token = adminToken();

        // 1 — create the user record
        try {
            http.post()
                    .uri(baseUrl + "/admin/realms/" + realm + "/users")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username",  username,
                            "enabled",   true,
                            "credentials", List.of()
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new UsernameConflictException(username);
            }
            log.error("Keycloak user creation failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RegistrationException("Could not create account: " + e.getStatusCode());
        }

        // 2 — look up the newly created user to get its ID
        String newToken = adminToken();
        List<Map<?, ?>> users = http.get()
                .uri(baseUrl + "/admin/realms/" + realm + "/users?username={u}&exact=true", username)
                .header("Authorization", "Bearer " + newToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<?, ?>>>() {});

        if (users == null || users.isEmpty()) {
            throw new RegistrationException("User was created but could not be found");
        }
        String userId = (String) users.get(0).get("id");

        // 3 — set the password
        try {
            http.put()
                    .uri(baseUrl + "/admin/realms/" + realm + "/users/" + userId + "/reset-password")
                    .header("Authorization", "Bearer " + newToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", password, "temporary", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Keycloak password set failed: {}", e.getStatusCode());
            throw new RegistrationException("Account created but password could not be set");
        }

        log.info("Registered new user '{}' in realm '{}'", username, realm);
    }

    // ── Exceptions ─────────────────────────────────────────────────────

    public static class UsernameConflictException extends RuntimeException {
        public UsernameConflictException(String username) {
            super("Username already taken: " + username);
        }
    }

    public static class RegistrationException extends RuntimeException {
        public RegistrationException(String msg) { super(msg); }
    }
}
