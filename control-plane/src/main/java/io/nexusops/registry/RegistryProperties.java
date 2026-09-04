package io.nexusops.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Static service catalog and the known-good image allowlist for rollbacks.
 * Bound from {@code nexusops.registry.*}. Rollback targets not in {@link #knownGoodImages()}
 * are rejected before execution — the model cannot introduce an arbitrary image.
 */
@ConfigurationProperties(prefix = "nexusops.registry")
public record RegistryProperties(
        List<ServiceDescriptor> services,
        List<String> knownGoodImages) {

    public RegistryProperties {
        services = services == null ? List.of() : List.copyOf(services);
        knownGoodImages = knownGoodImages == null ? List.of() : List.copyOf(knownGoodImages);
    }

    /**
     * A monitored service.
     *
     * @param ref          container/service name used as the target reference
     * @param environment  PROD or STAGING
     * @param healthUrl    HTTP health endpoint the watcher polls
     * @param cache        true if this is an ephemeral cache (eligible for CLEAR_CACHE)
     */
    public record ServiceDescriptor(String ref, String environment, String healthUrl,
                                    boolean cache) {
    }
}
