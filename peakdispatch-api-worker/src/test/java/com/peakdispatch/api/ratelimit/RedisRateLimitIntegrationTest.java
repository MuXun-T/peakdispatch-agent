package com.peakdispatch.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peakdispatch.adapter.redis.RedisAgentExecutionConcurrency;
import com.peakdispatch.application.agent.AgentConcurrencyLimitException;
import com.peakdispatch.application.agent.AgentExecutionConcurrency;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimitIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisFixedWindowRateLimiter rateLimiter;

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        rateLimiter = new RedisFixedWindowRateLimiter(redis, "limit-it");
    }

    @AfterEach
    void close() {
        if (factory != null) factory.destroy();
    }

    @Test
    void requestRate_allowsConfiguredWindowThenReturnsRetryDelay() {
        String identity = UUID.randomUUID().toString();

        assertThat(rateLimiter.acquire("user", identity, 2, 2_000)).isZero();
        assertThat(rateLimiter.acquire("user", identity, 2, 2_000)).isZero();
        assertThat(rateLimiter.acquire("user", identity, 2, 2_000)).isPositive();
    }

    @Test
    void requestRate_isolatedByRegionAndRecoversAfterWindowExpiry() throws InterruptedException {
        assertThat(rateLimiter.acquire("region", "north", 1, 120)).isZero();
        assertThat(rateLimiter.acquire("region", "south", 1, 120)).isZero();
        assertThat(rateLimiter.acquire("region", "north", 1, 120)).isPositive();
        Thread.sleep(160);
        assertThat(rateLimiter.acquire("region", "north", 1, 120)).isZero();
    }

    @Test
    void concurrentAgentRuns_neverExceedPerUserLimitAndReleaseOnCompletion() throws Exception {
        AgentExecutionConcurrency concurrency = new RedisAgentExecutionConcurrency(
                redis, "limit-it", 4, 2, 10);
        AtomicInteger acquired = new AtomicInteger();
        List<AgentExecutionConcurrency.Lease> leases = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<Void>> requests = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(index -> (Callable<Void>) () -> {
                        try {
                            leases.add(concurrency.acquireRun("same-user"));
                            acquired.incrementAndGet();
                        } catch (AgentConcurrencyLimitException expected) {
                            assertThat(expected.retryAfterSeconds()).isPositive();
                        }
                        return null;
                    }).toList();
            executor.invokeAll(requests);
        }
        assertThat(acquired).hasValue(4);
        leases.forEach(AgentExecutionConcurrency.Lease::close);
        try (AgentExecutionConcurrency.Lease ignored = concurrency.acquireRun("same-user")) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void modelConcurrency_rejectsAtLimitAndRecoversWhenLeaseCloses() {
        AgentExecutionConcurrency concurrency = new RedisAgentExecutionConcurrency(
                redis, "limit-it", 3, 1, 10);
        AgentExecutionConcurrency.Lease lease = concurrency.acquireModel("model-a");
        assertThatThrownBy(() -> concurrency.acquireModel("model-a"))
                .isInstanceOf(AgentConcurrencyLimitException.class)
                .hasMessage("MODEL_CONCURRENCY_LIMIT");
        lease.close();
        try (AgentExecutionConcurrency.Lease ignored = concurrency.acquireModel("model-a")) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void redisStateRebuiltAfterFlushAllowsRequestsAgain() {
        String identity = UUID.randomUUID().toString();
        assertThat(rateLimiter.acquire("api", identity, 1, 5_000)).isZero();
        assertThat(rateLimiter.acquire("api", identity, 1, 5_000)).isPositive();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        assertThat(rateLimiter.acquire("api", identity, 1, 5_000)).isZero();
    }
}
