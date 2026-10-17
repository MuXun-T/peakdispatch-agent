package com.peakdispatch.api.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RedisHttpRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private final RedisFixedWindowRateLimiter limiter;
    private final ObjectMapper mapper;
    private final PeakDispatchMetrics metrics;
    private final String environment;
    private final long windowMillis;
    private final int userLimit;
    private final int roleLimit;
    private final int regionLimit;
    private final int apiLimit;
    private final boolean enabled;

    public RedisHttpRateLimitFilter(RedisFixedWindowRateLimiter limiter, ObjectMapper mapper,
                                    PeakDispatchMetrics metrics,
                                    @Value("${peakdispatch.redis.environment:demo}") String environment,
                                    @Value("${peakdispatch.rate-limit.window-ms:1000}") long windowMillis,
                                    @Value("${peakdispatch.rate-limit.user-per-window:60}") int userLimit,
                                    @Value("${peakdispatch.rate-limit.role-per-window:600}") int roleLimit,
                                    @Value("${peakdispatch.rate-limit.region-per-window:300}") int regionLimit,
                                    @Value("${peakdispatch.rate-limit.api-per-window:120}") int apiLimit,
                                    @Value("${peakdispatch.rate-limit.enabled:true}") boolean enabled) {
        if (environment == null || !environment.matches("[A-Za-z0-9_-]{1,32}") || windowMillis < 100
                || userLimit < 1 || roleLimit < 1 || regionLimit < 1 || apiLimit < 1) {
            throw new IllegalArgumentException("Rate-limit settings are invalid");
        }
        this.limiter = limiter;
        this.mapper = mapper;
        this.metrics = metrics;
        this.environment = environment;
        this.windowMillis = windowMillis;
        this.userLimit = userLimit;
        this.roleLimit = roleLimit;
        this.regionLimit = regionLimit;
        this.apiLimit = apiLimit;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest downstream = request;
        String region = request.getParameter("regionId");
        if (request.getContentType() != null
                && request.getContentType().toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            downstream = new BufferedBodyRequest(request, body);
            if (body.length <= MAX_BODY_BYTES) region = region == null ? bodyRegion(body) : region;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String user = authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())
                ? "anonymous" : authentication.getName();
        String role = authentication == null ? "anonymous" : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5)).sorted().findFirst().orElse("authenticated");
        String regionValue = safeDimension(region) ? region : "all";
        String api = request.getMethod().toUpperCase(Locale.ROOT) + ":" + normalizePath(request.getRequestURI());
        List<Dimension> dimensions = List.of(
                new Dimension("user", user, userLimit),
                new Dimension("role", role, roleLimit),
                new Dimension("region", regionValue, regionLimit),
                new Dimension("api", api, apiLimit));
        try {
            for (Dimension dimension : dimensions) {
                long retryMillis = consume(dimension);
                if (retryMillis > 0) {
                    metrics.rateLimited(dimension.name());
                    writeError(response, 429, "RATE_LIMITED", retryMillis,
                            isWrite(request) ? "Retry the write with the same Idempotency-Key" : "Request rate limit exceeded");
                    return;
                }
            }
        } catch (RuntimeException unavailable) {
            writeError(response, 503, "RATE_LIMIT_BACKEND_UNAVAILABLE", 1000,
                    "Rate-limit state is unavailable; retry the request with the same Idempotency-Key");
            return;
        }
        chain.doFilter(downstream, response);
    }

    private long consume(Dimension dimension) {
        long started = System.nanoTime();
        try {
            long retryMillis = limiter.acquire(dimension.name(), dimension.value(), dimension.limit(), windowMillis);
            metrics.redisCall(java.time.Duration.ofNanos(System.nanoTime() - started), true);
            return retryMillis;
        } catch (RuntimeException failure) {
            metrics.redisCall(java.time.Duration.ofNanos(System.nanoTime() - started), false);
            throw failure;
        }
    }

    private String bodyRegion(byte[] body) {
        try {
            JsonNode value = mapper.readTree(body);
            return value == null || !value.hasNonNull("regionId") ? null : value.get("regionId").asText();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean safeDimension(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static String normalizePath(String path) {
        return path.replaceAll("(?i)/[0-9a-f]{8}-[0-9a-f-]{27,36}(?=/|$)", "/{id}")
                .replaceAll("/[0-9]{1,20}(?=/|$)", "/{id}");
    }

    private static boolean isWrite(HttpServletRequest request) {
        return !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod().toUpperCase(Locale.ROOT));
    }

    private static void writeError(HttpServletResponse response, int status, String code,
                                   long retryMillis, String message) throws IOException {
        long retrySeconds = Math.max(1, (retryMillis + 999) / 1000);
        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(retrySeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String traceId = MDC.get("traceId");
        new ObjectMapper().writeValue(response.getOutputStream(), java.util.Map.of(
                "error", java.util.Map.of("code", code, "message", message, "retryable", true),
                "traceId", traceId == null ? UUID.randomUUID().toString() : traceId));
    }

    private record Dimension(String name, String value, int limit) {
    }

    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
            };
        }

        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
