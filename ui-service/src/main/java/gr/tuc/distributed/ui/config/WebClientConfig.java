package gr.tuc.distributed.ui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client used by the UI Service to call the Manager Service.
 * The JWT from the incoming request is forwarded via RequestHeaderInterceptor.
 */
@Configuration
public class WebClientConfig {

    @Value("${app.manager.base-url}")
    private String managerBaseUrl;

    @Bean
    public RestClient managerRestClient() {
        return RestClient.builder()
                .baseUrl(managerBaseUrl)
                .build();
    }
}
