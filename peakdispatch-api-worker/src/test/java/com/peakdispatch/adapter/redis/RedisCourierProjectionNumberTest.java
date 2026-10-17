package com.peakdispatch.adapter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisCourierProjectionNumberTest {
    @Test
    void parsesRedisSortedSetEpochScoresReturnedAsDoubles() {
        assertThat(RedisCourierProjection.number(1_791_234_567_890d, 0))
                .isEqualTo(1_791_234_567_890L);
    }
}
