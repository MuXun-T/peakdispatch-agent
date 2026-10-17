package com.peakdispatch.adapter.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;

class RedisStreamConsumerTest {
    @Test
    void busyGroupFailure_whenWrappedByRedisSystemException_isRecognized() {
        RuntimeException failure = new RedisSystemException("Error in execution",
                new RedisBusyException("BUSYGROUP Consumer Group name already exists"));

        assertThat(RedisStreamConsumer.isBusyGroupFailure(failure)).isTrue();
    }

    @Test
    void unrelatedRedisFailure_isNotRecognizedAsBusyGroup() {
        RuntimeException failure = new RedisSystemException("Error in execution",
                new IllegalStateException("ERR stream is unavailable"));

        assertThat(RedisStreamConsumer.isBusyGroupFailure(failure)).isFalse();
    }
}
