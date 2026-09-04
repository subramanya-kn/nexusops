package io.nexusops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NexusOps control plane.
 *
 * <p>The control plane is the ONLY component that holds infrastructure credentials
 * and the ONLY component that can mutate infrastructure. The reasoning plane
 * (Python/LangGraph) proposes; this plane disposes — after policy + approval gating.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class NexusOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NexusOpsApplication.class, args);
    }
}
