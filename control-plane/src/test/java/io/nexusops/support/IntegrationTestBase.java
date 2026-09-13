package io.nexusops.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers fixture: real Postgres + real Keycloak with the committed realm
 * imported, matching how the brief wants Phase 2 integration tests run.
 *
 * <p><b>Deliberately not {@code @Testcontainers}/{@code @Container}.</b> That annotation
 * pair calls {@code stop()} on static container fields in each annotated class's own
 * {@code @AfterAll} — fine for one class, but every subclass of this base gets its own
 * independent extension instance, so whichever subclass's tests finish first stops these
 * containers out from under every subclass that runs after it (its {@code
 * KEYCLOAK.getAuthServerUrl()} still returns the now-dead port, since the container object
 * doesn't forget its last mapping). Found via a real Testcontainers run: {@code
 * ReasoningPlaneClient403Test} got "Connection refused" against Keycloak's JWK endpoint on
 * every single test, consistently, in both a local run and CI — not a flaky timing issue,
 * this class's containers really were already stopped by {@code AuditTamperIntegrationTest}
 * finishing first. This is Testcontainers' own documented "singleton container" pattern:
 * start once in a static initializer, never stop explicitly, and let Ryuk (or the JVM
 * exiting) reap them when the whole test run ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES;
    static final KeycloakContainer KEYCLOAK;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("nexusops")
                    .withUsername("nexusops")
                    .withPassword("nexusops");
            POSTGRES.start();
            KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:25.0")
                    .withRealmImportFile("keycloak/nexusops-realm.json");
            KEYCLOAK.start();
        } else {
            POSTGRES = null;
            KEYCLOAK = null;
        }
    }

    /** Mirrors {@code @Testcontainers(disabledWithoutDocker = true)}'s clean-skip behavior. */
    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available -- skipping integration test");
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Null when Docker isn't available (see the static initializer above) -- registered
        // as harmless placeholders so context preparation doesn't NPE before @BeforeAll's
        // assumeTrue gets a chance to skip the class cleanly.
        if (POSTGRES == null || KEYCLOAK == null) {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://unavailable/nexusops");
            registry.add("spring.datasource.username", () -> "unavailable");
            registry.add("spring.datasource.password", () -> "unavailable");
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                    () -> "http://unavailable/certs");
            return;
        }
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
