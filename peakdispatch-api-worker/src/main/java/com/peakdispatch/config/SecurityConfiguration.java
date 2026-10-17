package com.peakdispatch.config;

import com.peakdispatch.application.security.SensitiveDataRedactor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.peakdispatch.api.idempotency.HttpIdempotencyFilter;
import com.peakdispatch.api.ratelimit.RedisHttpRateLimitFilter;
import com.peakdispatch.api.system.RequestContext;
import com.peakdispatch.api.trace.JsonSecurityErrorWriter;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    FilterRegistrationBean<HttpIdempotencyFilter> disableServletRegistration(HttpIdempotencyFilter filter) {
        FilterRegistrationBean<HttpIdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RedisHttpRateLimitFilter> disableRateLimitServletRegistration(
            RedisHttpRateLimitFilter filter) {
        FilterRegistrationBean<RedisHttpRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, HttpIdempotencyFilter idempotencyFilter,
                                            RedisHttpRateLimitFilter rateLimitFilter,
                                            JsonSecurityErrorWriter errorWriter, JdbcTemplate jdbc) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> {
            recordDenied(jdbc, "anonymous", request.getRequestURI(), request.getMethod());
            errorWriter.write(response, 401, "UNAUTHENTICATED", "Authentication is required");
        };
        AccessDeniedHandler deniedHandler = (request, response, exception) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String actor = authentication == null ? "anonymous" : authentication.getName();
            recordDenied(jdbc, actor, request.getRequestURI(), request.getMethod());
            errorWriter.write(response, 403, "FORBIDDEN", "The operation is not permitted");
        };

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .addFilterAfter(rateLimitFilter, AuthorizationFilter.class)
                .addFilterAfter(idempotencyFilter, RedisHttpRateLimitFilter.class)
                .build();
    }

    private static void recordDenied(JdbcTemplate jdbc, String actor, String path, String method) {
        if (!(path.startsWith("/api/v1/ops/") || path.startsWith("/api/v1/dispatch/policies")
                || path.startsWith("/api/v1/knowledge/"))) return;
        try {
            UUID auditId = UUID.randomUUID();
            byte[] id = ByteBuffer.allocate(16).putLong(auditId.getMostSignificantBits())
                    .putLong(auditId.getLeastSignificantBits()).array();
            jdbc.update("""
                    INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision,
                        trace_id, details_redacted, created_at)
                    VALUES (?, ?, 'OPS_ACCESS_DENIED', 'HTTP_ENDPOINT', ?, 'DENIED', ?, ?, ?)
                    """, id, actor, path, RequestContext.traceId(), SensitiveDataRedactor.redact("method=" + method),
                    Timestamp.from(Instant.now()));
        } catch (RuntimeException exception) {
            log.warn("Could not record denied operations access for {}", path, exception);
        }
    }

    @Bean
    PasswordEncoder passwordEncoder(@Value("${peakdispatch.security.bcrypt-strength:10}") int strength) {
        if (strength < 4 || strength > 16) {
            throw new IllegalArgumentException("BCrypt strength must be between 4 and 16");
        }
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    @Profile({"demo", "test"})
    UserDetailsService demoUserDetailsService(DemoUsersProperties properties, PasswordEncoder encoder) {
        Map<String, String> encodedPasswords = new HashMap<>();
        List<UserDetails> users = properties.allIdentities().stream()
                .map(identity -> {
                    List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + identity.role()));
                    identity.regionScopes().forEach(region ->
                            authorities.add(new SimpleGrantedAuthority("REGION_" + region)));
                    return User.withUsername(identity.username())
                            .password(encodedPasswords.computeIfAbsent(identity.password(), encoder::encode))
                            .authorities(authorities)
                            .build();
                })
                .toList();
        return new InMemoryUserDetailsManager(users);
    }
}
