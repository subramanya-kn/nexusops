package io.nexusops.security;

import io.nexusops.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reasoning-plane authenticates with client-credentials and a read-only scope only —
 * it never receives a realm/client role. This is the test the brief calls out explicitly:
 * every mutating route must reject it with 403, against a real Postgres + real Keycloak,
 * not a mocked security context.
 */
class ReasoningPlaneClient403Test extends IntegrationTestBase {

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenFor("reasoning-plane", "reasoning-plane-secret"));
        return headers;
    }

    @Test
    void createIncidentIsForbidden() {
        var body = new io.nexusops.incident.IncidentController.CreateIncidentRequest(
                "payment-svc", io.nexusops.incident.Environment.PROD, "test signal");
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/incidents", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void triggerDiagnosisIsForbidden() {
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/incidents/nonexistent/diagnose", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approveIsForbidden() {
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/approvals/nonexistent/approve", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectIsForbidden() {
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/approvals/nonexistent/reject", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void policyReloadIsForbidden() {
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/admin/policy/reload", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void killSwitchEngageIsForbidden() {
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/admin/execution/kill-switch/engage", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void killSwitchReleaseIsForbidden() {
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/admin/execution/kill-switch/release", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void readEndpointsRemainAccessibleToTheReadOnlyClient() {
        // Read routes are open to any authenticated principal — confirms this is a
        // least-privilege distinction (read allowed, write forbidden), not a blanket deny.
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/incidents", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
