package io.nexusops.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Probes a service's HTTP health endpoint. Used both for detection (the watcher) and for
 * post-execution verification (re-checking the original signal).
 */
@Component
public class HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HealthProbe.class);

    private final RestClient client;

    public HealthProbe() {
        this.client = RestClient.builder()
                .requestFactory(clientRequestFactory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        return factory;
    }

    /** True if the endpoint returns 2xx within the timeout. */
    public boolean isHealthy(String healthUrl) {
        if (healthUrl == null || healthUrl.isBlank()) {
            return true; // nothing to probe
        }
        try {
            var response = client.get().uri(healthUrl).retrieve().toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Health probe failed for {}: {}", healthUrl, e.getMessage());
            return false;
        }
    }
}
