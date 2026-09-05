package io.nexusops.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reasoning-plane service client authenticates via client-credentials with only a
 * read scope and no realm/client roles. This converter is what makes that enforceable:
 * it must never manufacture a ROLE_* authority the token doesn't actually carry.
 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("test-subject");
    }

    @Test
    void realmRolesBecomeRoleAuthorities() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of("operator", "approver")))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_operator", "ROLE_approver");
    }

    @Test
    void clientRolesBecomeRoleAuthorities() {
        Jwt jwt = baseJwt()
                .claim("resource_access", Map.of(
                        "nexusops-cli", Map.of("roles", List.of("admin"))))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting(Object::toString).contains("ROLE_admin");
    }

    @Test
    void scopesBecomeScopeAuthorities() {
        Jwt jwt = baseJwt().claim("scope", "nexus.read").build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting(Object::toString).contains("SCOPE_nexus.read");
    }

    @Test
    void readOnlyServiceClientGetsNoRoleAuthorityAtAll() {
        // This is the property the 403 integration test relies on: a token with only
        // a read scope and no realm_access/resource_access claims yields zero ROLE_*
        // authorities, so every @PreAuthorize("hasRole(...)") on a mutating route fails.
        Jwt jwt = baseJwt().claim("scope", "nexus.read").build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .noneMatch(a -> a.startsWith("ROLE_"));
    }

    @Test
    void missingClaimsProduceNoAuthoritiesRatherThanFailing() {
        Jwt jwt = baseJwt().build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }
}
