package com.peakdispatch.api.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakdispatch.adapter.redis.RedisKeyspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;

/**
 * Real Redis Cluster contract test. It is intentionally skipped unless the
 * caller supplies a cluster node through PD_REDIS_CLUSTER_HOST/PORT.
 */
class RedisClusterScriptContractTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach
    void connectWhenConfigured() {
        String host = System.getenv().getOrDefault("PD_REDIS_CLUSTER_HOST", "");
        String port = System.getenv().getOrDefault("PD_REDIS_CLUSTER_PORT", "");
        Assumptions.assumeTrue(!host.isBlank() && !port.isBlank(),
                "NOT RUN: PD_REDIS_CLUSTER_HOST/PORT are not configured");
        factory = new LettuceConnectionFactory(new RedisClusterConfiguration(List.of(host + ":" + port)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void close() {
        if (factory != null) factory.destroy();
    }

    @Test
    void everyLuaKeyGroupHasOneActualClusterSlotAndRunsWithoutCrossSlot() throws Exception {
        String environment = "contract";
        UUID courier = UUID.randomUUID();
        List<String> rider = RedisKeyspace.riderKeys(environment, courier);
        List<String> region = RedisKeyspace.regionIndexKeys(environment, "contract-region");
        assertThat(actualSlots(rider)).containsOnly(RedisKeyspace.keySlot(rider.getFirst()));
        assertThat(actualSlots(region)).containsOnly(RedisKeyspace.keySlot(region.getFirst()));
        assertThat(actualSlots(RedisKeyspace.offerDelayKeys(environment)))
                .containsOnly(RedisKeyspace.keySlot(RedisKeyspace.offerDelayKeys(environment).getFirst()));
        List<String> dlq = RedisKeyspace.consumerDeadLetterKeys(environment);
        assertThat(actualSlots(dlq)).containsOnly(RedisKeyspace.keySlot(dlq.getFirst()));

        String presence = rider.get(0);
        String capacity = rider.get(1);
        String leases = rider.get(2);
        String expiry = rider.get(3);
        redis.opsForHash().putAll(capacity, java.util.Map.of("version", "1", "committed", "0",
                "reserved", "0", "limit", "2", "ready", "1"));
        eval("redis/lua/start-presence.lua", List.of(presence), "1", "contract-region");
        eval("redis/lua/update-presence.lua", List.of(presence), "1", "contract-region", "1",
                "30.25", "120.16", "LIVE", Long.toString(Instant.now().toEpochMilli()));
        eval("redis/lua/index-presence.lua", region, "1", "1", courier.toString(), "120.16", "30.25",
                Long.toString(Instant.now().toEpochMilli()));
        eval("redis/lua/fence-presence.lua", region, "1", "1", courier.toString());
        eval("redis/lua/prune-heartbeats.lua", region, "90000", "100");
        eval("redis/lua/reserve-lease.lua", List.of(presence, capacity, leases, expiry),
                UUID.randomUUID().toString(), "token", "2", "90000", "10000", "contract-region", "1", "1", "1");
        eval("redis/lua/release-lease.lua", List.of(leases, expiry), UUID.randomUUID().toString(), "missing");
        eval("redis/lua/project-capacity.lua", List.of(capacity, leases, expiry), "2", "0", "0", "2", "");
        eval("redis/lua/stop-presence.lua", List.of(presence), "2");
        List<String> offers = RedisKeyspace.offerDelayKeys(environment);
        String delayedOffer = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        eval("redis/lua/schedule-offer-due.lua", offers, delayedOffer, Long.toString(now - 1), Long.toString(now));
        eval("redis/lua/claim-offer-due.lua", offers, delayedOffer, "claim",
                Long.toString(now), "30000");
        eval("redis/lua/release-offer-due.lua", offers, delayedOffer, "claim", Long.toString(now - 1));
        eval("redis/lua/claim-offer-due.lua", offers, delayedOffer, "claim-2",
                Long.toString(now), "30000");
        eval("redis/lua/complete-offer-due.lua", offers, delayedOffer, "claim-2");
        String sourceRecord = "contract|1-0";
        String firstDlqId = evalText("redis/lua/publish-consumer-dlq.lua", dlq,
                sourceRecord, "eventId", UUID.randomUUID().toString(), "failureCode", "TEST");
        String duplicateDlqId = evalText("redis/lua/publish-consumer-dlq.lua", dlq,
                sourceRecord, "eventId", UUID.randomUUID().toString(), "failureCode", "TEST");
        assertThat(duplicateDlqId).isEqualTo(firstDlqId);
    }

    private List<Integer> actualSlots(List<String> keys) {
        return keys.stream().map(key -> redis.execute((RedisCallback<Integer>) connection -> {
            Object value = connection.execute("CLUSTER", bytes("KEYSLOT"), bytes(key));
            return value instanceof byte[] bytes ? Integer.parseInt(new String(bytes, StandardCharsets.UTF_8))
                    : Integer.parseInt(String.valueOf(value));
        })).toList();
    }

    private void eval(String path, List<String> keys, String... args) throws Exception {
        RedisKeyspace.requireSameSlot(keys);
        String source;
        try (var input = new ClassPathResource(path).getInputStream()) {
            source = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        redis.execute(new DefaultRedisScript<>(source, Long.class), keys, (Object[]) args);
    }

    private String evalText(String path, List<String> keys, String... args) throws Exception {
        RedisKeyspace.requireSameSlot(keys);
        String source;
        try (var input = new ClassPathResource(path).getInputStream()) {
            source = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        return redis.execute(new DefaultRedisScript<>(source, String.class), keys, (Object[]) args);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
