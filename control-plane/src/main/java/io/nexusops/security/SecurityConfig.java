package io.nexusops.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 resource-server security.
 *
 * <p>Design decisions:
 * <ul>
 *   <li><b>Stateless</b> — no session; every request re-authenticates from its JWT.</li>
 *   <li><b>Deny by default</b> — only actuator health/info + OpenAPI are public;
 *       everything else requires authentication, and mutating routes require a role
 *       (enforced with {@code @PreAuthorize} at the method level).</li>
 *   <li>The reasoning plane authenticates via client-credentials and receives only
 *       {@code SCOPE_nexus.read}; it can therefore never satisfy a mutating role.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html").permitAll()
                // Read endpoints: any authenticated principal (operator, approver, admin,
                // or the reasoning-plane service client).
                .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                // Everything else (all mutations) requires authentication AND a method-level role.
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter())));
        return http.build();
    }
}
