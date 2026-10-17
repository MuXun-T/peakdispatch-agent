package com.peakdispatch.adapter.redis;

import com.peakdispatch.application.dispatch.RedisAvailability;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisAvailabilityProbe implements RedisAvailability {
    private final StringRedisTemplate redis;

    public RedisAvailabilityProbe(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean available() {
        try {
            return "PONG".equalsIgnoreCase(redis.execute((RedisCallback<String>) connection -> connection.ping()));
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
