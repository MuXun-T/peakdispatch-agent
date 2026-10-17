package com.peakdispatch.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class RedisHttpRateLimitFilterTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rateLimitedWriteReturns429RetryAfterAndPreservesIdempotencyGuidance() throws Exception {
        RedisFixedWindowRateLimiter limiter = mock(RedisFixedWindowRateLimiter.class);
        when(limiter.acquire(anyString(), anyString(), anyInt(), anyLong())).thenReturn(2_500L);
        RedisHttpRateLimitFilter filter = filter(limiter);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED", "same Idempotency-Key");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void normalRequestConsumesUserRoleRegionAndApiBudgetsThenContinues() throws Exception {
        RedisFixedWindowRateLimiter limiter = mock(RedisFixedWindowRateLimiter.class);
        when(limiter.acquire(anyString(), anyString(), anyInt(), anyLong())).thenReturn(0L);
        RedisHttpRateLimitFilter filter = filter(limiter);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(limiter).acquire("user", "ops-user", 60, 1_000);
        verify(limiter).acquire("role", "OPS_VIEWER", 600, 1_000);
        verify(limiter).acquire("region", "north", 300, 1_000);
        verify(limiter).acquire("api", "POST:/api/v1/orders", 120, 1_000);
    }

    @Test
    void putJsonRequestUsesItsRegionBudget() throws Exception {
        RedisFixedWindowRateLimiter limiter = mock(RedisFixedWindowRateLimiter.class);
        when(limiter.acquire(anyString(), anyString(), anyInt(), anyLong())).thenReturn(0L);
        RedisHttpRateLimitFilter filter = filter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/couriers/courier-1/presence");
        request.setContentType("application/json");
        request.setContent("{\"online\":true,\"regionId\":\"north\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(limiter).acquire("region", "north", 300, 1_000);
        verify(limiter).acquire("api", "PUT:/api/v1/couriers/courier-1/presence", 120, 1_000);
    }

    @Test
    void redisFailureFailsClosedWith503AndRetryAfter() throws Exception {
        RedisFixedWindowRateLimiter limiter = mock(RedisFixedWindowRateLimiter.class);
        when(limiter.acquire(anyString(), anyString(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisHttpRateLimitFilter filter = filter(limiter, registry);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_BACKEND_UNAVAILABLE",
                "same Idempotency-Key");
        assertThat(registry.get("peakdispatch.redis.errors").counter().count()).isEqualTo(1);
    }

    private static RedisHttpRateLimitFilter filter(RedisFixedWindowRateLimiter limiter) {
        return filter(limiter, new SimpleMeterRegistry());
    }

    private static RedisHttpRateLimitFilter filter(RedisFixedWindowRateLimiter limiter, SimpleMeterRegistry registry) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ops-user", "n/a", AuthorityUtils.createAuthorityList("ROLE_OPS_VIEWER", "REGION_north")));
        return new RedisHttpRateLimitFilter(limiter, new ObjectMapper(),
                new PeakDispatchMetrics(registry), "test", 1_000,
                60, 600, 300, 120, true);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("Idempotency-Key", "same-key-on-retry");
        request.setContentType("application/json");
        request.setContent("{\"regionId\":\"north\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
