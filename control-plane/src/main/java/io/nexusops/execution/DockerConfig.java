package io.nexusops.execution;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Builds the Docker API client used by the capability executor.
 *
 * <p>This client — and the credentials/socket it wraps — lives ONLY in the control plane.
 * The reasoning plane has no equivalent and no network path to it. Bean creation is
 * defensive: if the daemon is unreachable, the app still starts and the gateway reports
 * itself unavailable (fail-closed at execution time rather than crash at boot).
 */
@Configuration
public class DockerConfig {

    private static final Logger log = LoggerFactory.getLogger(DockerConfig.class);

    @Bean
    public DockerClient dockerClient(
            @Value("${nexusops.docker.host:unix:///var/run/docker.sock}") String dockerHost) {
        try {
            DefaultDockerClientConfig config = DefaultDockerClientConfig
                    .createDefaultConfigBuilder()
                    .withDockerHost(dockerHost)
                    .build();
            DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .maxConnections(20)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .responseTimeout(Duration.ofSeconds(30))
                    .build();
            return DockerClientImpl.getInstance(config, httpClient);
        } catch (Exception e) {
            log.warn("Docker client init failed ({}). Executor will report unavailable.",
                    e.getMessage());
            // Return a client anyway; availability is checked at call time via a ping.
            DefaultDockerClientConfig config = DefaultDockerClientConfig
                    .createDefaultConfigBuilder().build();
            DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .build();
            return DockerClientImpl.getInstance(config, httpClient);
        }
    }
}
