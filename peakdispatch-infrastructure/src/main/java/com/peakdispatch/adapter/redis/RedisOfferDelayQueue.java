package com.peakdispatch.adapter.redis;

import com.peakdispatch.application.dispatch.OfferDelayQueue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/** Redis delay index with short claims; expiration is always rechecked in MySQL. */
@Component
@Profile("!test")
public class RedisOfferDelayQueue implements OfferDelayQueue {
    private static final long CLAIM_MILLIS = 30_000;

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String environment;
    private final DefaultRedisScript<Long> schedule;
    private final DefaultRedisScript<Long> claim;
    private final DefaultRedisScript<Long> complete;
    private final DefaultRedisScript<Long> release;

    public RedisOfferDelayQueue(StringRedisTemplate redis, Clock clock,
                                @Value("${peakdispatch.redis.environment:demo}") String environment) {
        this.redis = redis;
        this.clock = clock;
        this.environment = environment;
        this.schedule = script("redis/lua/schedule-offer-due.lua");
        this.claim = script("redis/lua/claim-offer-due.lua");
        this.complete = script("redis/lua/complete-offer-due.lua");
        this.release = script("redis/lua/release-offer-due.lua");
    }

    @Override
    public void schedule(UUID offerId, Instant dueAt) {
        List<String> keys = RedisKeyspace.offerDelayKeys(environment);
        RedisKeyspace.requireSameSlot(keys);
        redis.execute(schedule, keys, offerId.toString(), Long.toString(dueAt.toEpochMilli()),
                Long.toString(Instant.now(clock).toEpochMilli()));
    }

    @Override
    public List<DueOffer> claimDue(int limit, Instant now) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("delay claim limit must be 1..1000");
        List<String> keys = RedisKeyspace.offerDelayKeys(environment);
        RedisKeyspace.requireSameSlot(keys);
        var dueIds = redis.opsForZSet().rangeByScore(keys.get(0), Double.NEGATIVE_INFINITY, now.toEpochMilli(), 0, limit);
        var expiredClaims = redis.opsForZSet().rangeByScore(keys.get(1), Double.NEGATIVE_INFINITY, now.toEpochMilli(), 0, limit);
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (expiredClaims != null) expiredClaims.forEach(id -> addLimited(ids, id, limit));
        if (dueIds != null) dueIds.forEach(id -> addLimited(ids, id, limit));
        if (ids.isEmpty()) return List.of();
        List<DueOffer> claimed = new ArrayList<>();
        for (String id : ids) {
            UUID offerId;
            try { offerId = UUID.fromString(id); } catch (IllegalArgumentException ignored) { continue; }
            String token = UUID.randomUUID().toString();
            Long result = redis.execute(claim, keys, id, token,
                    Long.toString(now.toEpochMilli()), Long.toString(CLAIM_MILLIS));
            if (result == null || result != 1) continue;
            Object due = redis.opsForHash().get(keys.get(2), id);
            long dueAt = parseEpochMillis(due, now.toEpochMilli());
            claimed.add(new DueOffer(offerId, Instant.ofEpochMilli(dueAt), token));
        }
        return List.copyOf(claimed);
    }

    @Override
    public void complete(DueOffer offer) {
        List<String> keys = RedisKeyspace.offerDelayKeys(environment);
        RedisKeyspace.requireSameSlot(keys);
        redis.execute(complete, keys, offer.offerId().toString(), offer.claimToken());
    }

    @Override
    public void release(DueOffer offer, Instant retryAt) {
        List<String> keys = RedisKeyspace.offerDelayKeys(environment);
        RedisKeyspace.requireSameSlot(keys);
        redis.execute(release, keys, offer.offerId().toString(), offer.claimToken(),
                Long.toString(retryAt.toEpochMilli()));
    }

    private static void addLimited(java.util.Set<String> values, String value, int limit) {
        if (values.size() < limit) values.add(value);
    }

    private static long parseEpochMillis(Object value, long fallback) {
        if (value == null) return fallback;
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private static DefaultRedisScript<Long> script(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            String source = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, Long.class);
        } catch (IOException failure) {
            throw new IllegalStateException("Redis Lua script is missing: " + path, failure);
        }
    }
}
