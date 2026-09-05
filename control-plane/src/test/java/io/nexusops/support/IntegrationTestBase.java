package io.nexusops.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers fixture: real Postgres + real Keycloak with the committed realm
 * imported, matching how the brief wants Phase 2 integration tests run. {@code
 * disabledWithoutDocker = true} means these classes skip cleanly (not fail) in any
 * environment without a Docker daemon — required by the "environment reality" rule.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("nexusops")
                    .withUsername("nexusops")
                    .withPassword("nexusops");

    @Container
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:25.0")
                    .withRealmImportFile("keycloak/nexusops-realm.json");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/nexusops/protocol/openid-connect/certs");
    }

    @LocalServerPort
    protected int port;

    protected final TestRestTemplate rest = new TestRestTemplate();

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Client-credentials token for the given client, using the realm seeded by Phase 2. */
    protected String tokenFor(String clientId, String clientSecret) {
        String tokenUrl = KEYCLOAK.getAuthServerUrl()
                + "/realms/nexusops/protocol/openid-connect/token";
        org.springframework.util.MultiValueMap<String, String> body =
                new org.springframework.util.LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        var response = rest.postForEntity(tokenUrl,
                new org.springframework.http.HttpEntity<>(body, headers), TokenResponse.class);
        return java.util.Objects.requireNonNull(response.getBody()).accessToken();
    }

    protected record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken) {
    }
}
