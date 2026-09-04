package io.nexusops.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata; the spec is served at /v3/api-docs and can be committed for review. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nexusOpsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("NexusOps Control Plane API")
                .version("0.1.0")
                .description("Policy-gated autonomous incident remediation — control plane. "
                        + "The only component that can mutate infrastructure.")
                .license(new License().name("MIT")));
    }
}
