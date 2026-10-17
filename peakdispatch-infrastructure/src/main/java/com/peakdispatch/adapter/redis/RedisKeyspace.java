package com.peakdispatch.adapter.redis;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Canonical Redis key names and Redis Cluster hash-slot contracts.
 *
 * <p>The environment is a namespace prefix only. Hash tags are deliberately
 * scoped to the entity whose keys participate in one Lua invocation.</p>
 */
public final class RedisKeyspace {
    public static final int SLOT_COUNT = 16_384;

    private RedisKeyspace() {
    }

    public static String riderTag(String environment, UUID courierId) {
        return "{" + environment(environment) + ":" + courierId + "}";
    }

    public static List<String> riderKeys(String environment, UUID courierId) {
        String prefix = "pd:" + environment(environment) + ":courier:" + riderTag(environment, courierId);
        return List.of(prefix + ":presence", prefix + ":capacity", prefix + ":leases", prefix + ":lease-expiry");
    }

    public static String regionTag(String environment, String regionId) {
        return "{" + environment(environment) + ":" + safeRegion(regionId) + "}";
    }

    public static List<String> regionIndexKeys(String environment, String regionId) {
        String prefix = "pd:" + environment(environment) + ":geo:" + regionTag(environment, regionId);
        return List.of(prefix + ":members", prefix + ":versions", prefix + ":heartbeats");
    }

    public static String validateRegion(String regionId) {
        return safeRegion(regionId);
    }

    /** Delayed offer queues use one queue entity tag, so their Lua keys are co-located. */
    public static List<String> offerDelayKeys(String environment) {
        String prefix = "pd:" + environment(environment) + ":offers:{" + environment(environment) + "-offers}";
        return List.of(prefix + ":due", prefix + ":claims", prefix + ":payload", prefix + ":claim-owners");
    }

    public static List<String> consumerDeadLetterKeys(String environment) {
        String prefix = "pd:" + environment(environment) + ":stream:dlq:consumer:{"
                + environment(environment) + "-consumer-dlq}";
        return List.of(prefix, prefix + ":dedupe");
    }

    public static int keySlot(String key) {
        String hashInput = hashTag(key);
        byte[] bytes = hashInput.getBytes(StandardCharsets.UTF_8);
        int crc = 0;
        for (byte value : bytes) {
            crc ^= (value & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc % SLOT_COUNT;
    }

    public static void requireSameSlot(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("at least one Redis key is required");
        }
        int slot = keySlot(keys.getFirst());
        if (keys.stream().anyMatch(key -> keySlot(key) != slot)) {
            throw new IllegalArgumentException("Redis Lua keys must share one hash slot: " + keys);
        }
    }

    private static String hashTag(String key) {
        int start = key.indexOf('{');
        if (start >= 0) {
            int end = key.indexOf('}', start + 1);
            if (end > start + 1) return key.substring(start + 1, end);
        }
        return key;
    }

    private static String environment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Redis environment must be a simple identifier");
        }
        return value;
    }

    private static String safeRegion(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("regionId is not a valid Redis key component");
        }
        return value;
    }
}
