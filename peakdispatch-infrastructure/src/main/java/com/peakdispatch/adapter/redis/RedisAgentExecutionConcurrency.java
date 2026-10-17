package com.peakdispatch.adapter.redis;

import com.peakdispatch.application.agent.AgentConcurrencyLimitException;
import com.peakdispatch.application.agent.AgentConcurrencyUnavailableException;
import com.peakdispatch.application.agent.AgentExecutionConcurrency;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RedisAgentExecutionConcurrency implements AgentExecutionConcurrency {
    private static final DefaultRedisScript<Long> ACQUIRE = script("redis/lua/acquire-concurrency-lease.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("redis/lua/release-concurrency-lease.lua");

    private final StringRedisTemplate redis;
    private final String environment;
    private final int maxRunsPerUser;
    private final int maxModelCalls;
    private final long leaseMillis;

    public RedisAgentExecutionConcurrency(StringRedisTemplate redis,
                                          @Value("${peakdispatch.redis.environment:demo}") String environment,
                                          @Value("${peakdispatch.agent.concurrency.max-runs-per-user:3}") int maxRunsPerUser,
                                          @Value("${peakdispatch.agent.concurrency.max-model-calls:8}") int maxModelCalls,
                                          @Value("${peakdispatch.agent.concurrency.lease-seconds:180}") long leaseSeconds) {
        if (environment == null || !environment.matches("[A-Za-z0-9_-]{1,32}")
                || maxRunsPerUser < 1 || maxModelCalls < 1 || leaseSeconds < 1) {
            throw new IllegalArgumentException("Agent concurrency settings are invalid");
        }
        this.redis = redis;
        this.environment = environment;
        this.maxRunsPerUser = maxRunsPerUser;
        this.maxModelCalls = maxModelCalls;
        this.leaseMillis = Duration.ofSeconds(leaseSeconds).toMillis();
    }

    @Override
    public Lease acquireRun(String actorId) {
        return acquire("agent-run", actorId, maxRunsPerUser, "AGENT_RUN_CONCURRENCY_LIMIT");
    }

    @Override
    public Lease acquireModel(String modelId) {
        return acquire("model", modelId, maxModelCalls, "MODEL_CONCURRENCY_LIMIT");
    }

    @Override
    public Optional<Lease> tryAcquireExecution(String runId) {
        try {
            return Optional.of(acquire("agent-execution", runId, 1, "AGENT_RUN_ALREADY_EXECUTING"));
        } catch (AgentConcurrencyLimitException busy) {
            return Optional.empty();
        }
    }

    private Lease acquire(String type, String owner, int limit, String rejectionCode) {
        String key = "pd:" + environment + ":limit:concurrency:" + type + ":" + sha256(owner);
        String leaseId = UUID.randomUUID().toString();
        final Long retryMillis;
        try {
            retryMillis = redis.execute(ACQUIRE, List.of(key), Integer.toString(limit),
                    Long.toString(leaseMillis), leaseId);
        } catch (RuntimeException failure) {
            throw new AgentConcurrencyUnavailableException(failure);
        }
        if (retryMillis == null) throw new AgentConcurrencyUnavailableException(null);
        if (retryMillis > 0) {
            throw new AgentConcurrencyLimitException(rejectionCode, (retryMillis + 999) / 1000);
        }
        return () -> {
            try {
                redis.execute(RELEASE, List.of(key), leaseId);
            } catch (RuntimeException failure) {
                throw new AgentConcurrencyUnavailableException(failure);
            }
        };
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
