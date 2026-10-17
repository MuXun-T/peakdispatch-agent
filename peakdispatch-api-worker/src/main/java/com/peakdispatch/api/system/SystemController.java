package com.peakdispatch.api.system;

import com.peakdispatch.config.RuntimeProperties;
import com.peakdispatch.application.dispatch.DispatchReadiness;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class SystemController {
    private final RuntimeProperties runtime;
    private final Clock clock;
    private final Optional<DispatchReadiness> dispatchReadiness;
    private final boolean rateLimitEnabled;
    private final int userRateLimit;
    private final int roleRateLimit;
    private final int regionRateLimit;
    private final int apiRateLimit;
    private final String redisEnvironment;
    private final List<String> activeProfiles;

    public SystemController(RuntimeProperties runtime, Clock clock, Optional<DispatchReadiness> dispatchReadiness,
                            @Value("${peakdispatch.rate-limit.enabled:true}") boolean rateLimitEnabled,
                            @Value("${peakdispatch.rate-limit.user-per-window:60}") int userRateLimit,
                            @Value("${peakdispatch.rate-limit.role-per-window:600}") int roleRateLimit,
                            @Value("${peakdispatch.rate-limit.region-per-window:300}") int regionRateLimit,
                            @Value("${peakdispatch.rate-limit.api-per-window:120}") int apiRateLimit,
                            @Value("${peakdispatch.redis.environment:demo}") String redisEnvironment,
                            Environment environment) {
        this.runtime = runtime;
        this.clock = clock;
        this.dispatchReadiness = dispatchReadiness;
        this.rateLimitEnabled = rateLimitEnabled;
        this.userRateLimit = userRateLimit;
        this.roleRateLimit = roleRateLimit;
        this.regionRateLimit = regionRateLimit;
        this.apiRateLimit = apiRateLimit;
        this.redisEnvironment = redisEnvironment;
        this.activeProfiles = List.of(environment.getActiveProfiles());
    }

    @GetMapping("/info")
    public ApiResponse<SystemInfo> info(Authentication authentication) {
        return new ApiResponse<>(new SystemInfo(
                "PeakDispatch",
                runtime.role(),
                runtime.dataMode(),
                authentication.getName(),
                authentication.getAuthorities().stream().map(Object::toString).sorted().toList(),
                List.of("health", "request-tracing", "http-idempotency"),
                Instant.now(clock)));
    }

    @GetMapping("/recovery")
    public ApiResponse<RecoveryStatus> recoveryStatus() {
        return new ApiResponse<>(new RecoveryStatus(runtime.dataMode(),
                dispatchReady(),
                System.getProperty("java.version", "unknown"), rateLimitEnabled, userRateLimit,
                roleRateLimit, regionRateLimit, apiRateLimit, redisEnvironment, activeProfiles,
                Instant.now(clock)));
    }

    private boolean dispatchReady() {
        try {
            return dispatchReadiness.map(DispatchReadiness::ready).orElse(false);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public record SystemInfo(
            String name,
            String runtimeRole,
            String dataMode,
            String authenticatedAs,
            List<String> authorities,
            List<String> foundationalCapabilities,
            Instant asOf) {
    }

    public record RecoveryStatus(String dataMode, boolean dispatchReady, String javaVersion,
                                 boolean rateLimitEnabled, int userRateLimitPerWindow,
                                 int roleRateLimitPerWindow, int regionRateLimitPerWindow,
                                 int apiRateLimitPerWindow, String redisEnvironment,
                                 List<String> activeProfiles, Instant checkedAt) {
    }
}
