package io.nexusops.registry;

import io.nexusops.incident.Environment;
import io.nexusops.registry.RegistryProperties.ServiceDescriptor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** In-memory view of the monitored services and the rollback image allowlist. */
@Service
public class ServiceRegistry {

    private final Map<String, ServiceDescriptor> byRef;
    private final List<String> knownGoodImages;

    public ServiceRegistry(RegistryProperties props) {
        this.byRef = props.services().stream()
                .collect(Collectors.toMap(ServiceDescriptor::ref, Function.identity()));
        this.knownGoodImages = props.knownGoodImages();
    }

    public boolean exists(String ref) {
        return byRef.containsKey(ref);
    }

    public Optional<ServiceDescriptor> find(String ref) {
        return Optional.ofNullable(byRef.get(ref));
    }

    public List<ServiceDescriptor> all() {
        return List.copyOf(byRef.values());
    }

    public Environment environmentOf(String ref) {
        ServiceDescriptor d = byRef.get(ref);
        if (d == null) {
            return Environment.STAGING;
        }
        return "PROD".equalsIgnoreCase(d.environment()) ? Environment.PROD : Environment.STAGING;
    }

    public boolean isKnownGoodImage(String digest) {
        return knownGoodImages.contains(digest);
    }
}
