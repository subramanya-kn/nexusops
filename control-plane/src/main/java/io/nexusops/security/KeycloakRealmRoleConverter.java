package io.nexusops.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Keycloak realm roles and client roles into Spring authorities (ROLE_*).
 *
 * <p>Keycloak places realm roles under {@code realm_access.roles} and client roles
 * under {@code resource_access.<client>.roles}. Both are surfaced as {@code ROLE_<name>}.
 * The reasoning-plane service client carries only read scopes, so it never gains a
 * mutating role here.
 */
public final class KeycloakRealmRoleConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    @SuppressWarnings("unchecked")
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }

        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            for (Object client : resourceAccess.values()) {
                if (client instanceof Map<?, ?> clientMap
                        && clientMap.get("roles") instanceof List<?> clientRoles) {
                    for (Object role : clientRoles) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    }
                }
            }
        }

        // OAuth2 scopes -> SCOPE_* (used to pin the reasoning client to read-only).
        String scope = jwt.getClaimAsString("scope");
        if (scope != null) {
            for (String s : scope.split(" ")) {
                if (!s.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
                }
            }
        }

        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    Collection<GrantedAuthority> extractForTest(Jwt jwt) {
        return convert(jwt).getAuthorities().stream()
                .map(a -> (GrantedAuthority) a).toList();
    }
}
