package com.peakdispatch.api.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisFixedWindowRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = script();
    private final StringRedisTemplate redis;
    private final String environment;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redis,
                                       @Value("${peakdispatch.redis.environment:demo}") String environment) {
        if (environment == null || !environment.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Redis environment is invalid");
        }
        this.redis = redis;
        this.environment = environment;
    }

    public long acquire(String dimension, String identity, int limit, long windowMillis) {
        if (dimension == null || !dimension.matches("[a-z-]{1,32}") || identity == null
                || identity.length() > 256 || limit < 1 || windowMillis < 100) {
            throw new IllegalArgumentException("Rate-limit request is invalid");
        }
        String key = "pd:" + environment + ":limit:" + dimension + ":" + sha256(identity);
        Long retryMillis = redis.execute(SCRIPT, List.of(key), Integer.toString(limit), Long.toString(windowMillis));
        if (retryMillis == null) throw new IllegalStateException("Redis rate-limit script returned no result");
        return retryMillis;
    }

    private static DefaultRedisScript<Long> script() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/lua/rate-limit.lua"));
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
