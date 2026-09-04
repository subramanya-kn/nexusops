package io.nexusops.execution;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.nexusops.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * docker-java implementation of the capability gateway. Every method maps an
 * {@link io.nexusops.remediation.ActionType} to a specific docker-java API call — never a
 * shell, never an {@code exec}, never a command string.
 *
 * <p>Single-host demo semantics (documented, honest):
 * <ul>
 *   <li>{@code scaleService}: 0 = stop, &ge;1 = ensure running (no orchestrator here).</li>
 *   <li>{@code clearCache}: restart the ephemeral cache container.</li>
 *   <li>{@code rotateLog}: not applicable without host log-driver access — returns a typed
 *       unsupported outcome rather than pretending. See TODO(human).</li>
 * </ul>
 */
@Component
public class DockerInfrastructureGateway implements InfrastructureGateway {

    private static final Logger log = LoggerFactory.getLogger(DockerInfrastructureGateway.class);

    private final DockerClient docker;

    public DockerInfrastructureGateway(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public boolean isAvailable() {
        try {
            docker.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String captureState(String targetRef) {
        try {
            InspectContainerResponse c = inspect(targetRef);
            var state = c.getState();
            return Json.write(Map.of(
                    "targetRef", targetRef,
                    "running", Boolean.TRUE.equals(state.getRunning()),
                    "status", String.valueOf(state.getStatus()),
                    "exitCode", state.getExitCodeLong() == null ? -1 : state.getExitCodeLong(),
                    "image", String.valueOf(c.getConfig().getImage()),
                    "restartCount", c.getRestartCount() == null ? 0 : c.getRestartCount()));
        } catch (Exception e) {
            return Json.write(Map.of("targetRef", targetRef, "error", "not-found-or-unavailable"));
        }
    }

    @Override
    public CapabilityOutcome restartContainer(String containerRef, String reason) {
        String pre = captureState(containerRef);
        try {
            String id = resolveId(containerRef);
            docker.restartContainerCmd(id).exec();
            return CapabilityOutcome.ok("restarted " + containerRef + " (" + reason + ")",
                    pre, captureState(containerRef));
        } catch (Exception e) {
            return CapabilityOutcome.failed("restart failed: " + e.getMessage(), pre);
        }
    }

    @Override
    public CapabilityOutcome scaleService(String serviceRef, int replicas) {
        String pre = captureState(serviceRef);
        try {
            String id = resolveId(serviceRef);
            if (replicas <= 0) {
                docker.stopContainerCmd(id).exec();
                return CapabilityOutcome.ok("scaled " + serviceRef + " to 0 (stopped)",
                        pre, captureState(serviceRef));
            }
            // Single-host: ensure the container is running (>=1). No orchestrator to fan out.
            InspectContainerResponse c = docker.inspectContainerCmd(id).exec();
            if (!Boolean.TRUE.equals(c.getState().getRunning())) {
                docker.startContainerCmd(id).exec();
            }
            return CapabilityOutcome.ok("ensured " + serviceRef + " running (replicas>=1)",
                    pre, captureState(serviceRef));
        } catch (Exception e) {
            return CapabilityOutcome.failed("scale failed: " + e.getMessage(), pre);
        }
    }

    @Override
    public CapabilityOutcome rollbackImage(String serviceRef, String toDigest) {
        String pre = captureState(serviceRef);
        try {
            InspectContainerResponse c = inspect(serviceRef);
            String name = c.getName().startsWith("/") ? c.getName().substring(1) : c.getName();
            String[] env = c.getConfig().getEnv();
            docker.stopContainerCmd(c.getId()).exec();
            docker.removeContainerCmd(c.getId()).withForce(true).exec();
            var create = docker.createContainerCmd(toDigest).withName(name);
            if (env != null) {
                create = create.withEnv(env);
            }
            if (c.getHostConfig() != null) {
                create = create.withHostConfig(c.getHostConfig());
            }
            CreateContainerResponse created = create.exec();
            docker.startContainerCmd(created.getId()).exec();
            return CapabilityOutcome.ok("rolled back " + serviceRef + " to " + toDigest,
                    pre, captureState(name));
        } catch (Exception e) {
            return CapabilityOutcome.failed("rollback failed: " + e.getMessage(), pre);
        }
    }

    @Override
    public CapabilityOutcome clearCache(String cacheRef) {
        // Cache is an ephemeral container; restarting it clears in-memory state.
        String pre = captureState(cacheRef);
        try {
            String id = resolveId(cacheRef);
            docker.restartContainerCmd(id).exec();
            return CapabilityOutcome.ok("cleared cache by restarting " + cacheRef,
                    pre, captureState(cacheRef));
        } catch (Exception e) {
            return CapabilityOutcome.failed("clear-cache failed: " + e.getMessage(), pre);
        }
    }

    @Override
    public CapabilityOutcome rotateLog(String containerRef) {
        // TODO(human): true log rotation needs host log-driver access (json-file path or a
        // logging sidecar). Not available in the single-host demo; returned as a typed,
        // honest unsupported outcome rather than a fake success.
        String pre = captureState(containerRef);
        return CapabilityOutcome.failed(
                "rotate-log not supported in single-host demo (needs host log-driver access)",
                pre);
    }

    private String resolveId(String ref) {
        return inspect(ref).getId();
    }

    private InspectContainerResponse inspect(String ref) {
        // Try direct inspect (id or exact name) first, then fall back to a name search.
        try {
            return docker.inspectContainerCmd(ref).exec();
        } catch (Exception ignored) {
            Optional<Container> match = findByName(ref);
            if (match.isPresent()) {
                return docker.inspectContainerCmd(match.get().getId()).exec();
            }
            throw new IllegalArgumentException("container not found: " + ref);
        }
    }

    private Optional<Container> findByName(String ref) {
        List<Container> containers = docker.listContainersCmd().withShowAll(true).exec();
        String needle = "/" + ref;
        return containers.stream()
                .filter(c -> c.getNames() != null)
                .filter(c -> {
                    for (String n : c.getNames()) {
                        if (n.equals(needle) || n.equals(ref) || n.endsWith(needle)) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst();
    }
}
