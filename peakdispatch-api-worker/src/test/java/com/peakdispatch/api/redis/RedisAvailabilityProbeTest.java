package com.peakdispatch.api.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakdispatch.adapter.redis.RedisAvailabilityProbe;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAvailabilityProbeTest {
    @Test
    void unavailableServerIsReportedAsUnavailable() {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300)).shutdownTimeout(Duration.ZERO).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), client);
        factory.afterPropertiesSet();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            assertThat(new RedisAvailabilityProbe(redis).available()).isFalse();
        } finally {
            factory.destroy();
        }
    }
}
