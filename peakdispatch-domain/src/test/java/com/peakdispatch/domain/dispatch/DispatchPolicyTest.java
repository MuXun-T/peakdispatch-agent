package com.peakdispatch.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DispatchPolicyTest {

    @Test
    void demoPolicy_usesConfiguredFiveRoundBackoff() {
        DispatchPolicy policy = DispatchPolicy.demo();

        assertThat(policy.maxRounds()).isEqualTo(5);
        assertThat(policy.retryBackoffSeconds()).containsExactly(5, 15, 30, 60);
        assertThat(policy.retryDelaySeconds(1)).isEqualTo(5);
        assertThat(policy.retryDelaySeconds(4)).isEqualTo(60);
    }

    @Test
    void policy_rejectsMoreThanFiveRoundsOrMismatchedBackoff() {
        assertThatThrownBy(() -> new DispatchPolicy(1, 1, 1, 20, 90, 30,
                6, new long[] {5, 15, 30, 60, 120}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchPolicy(1, 1, 1, 20, 90, 30,
                5, new long[] {5, 15, 30}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchPolicy(Double.NaN, 1, 1, 20, 90, 30,
                5, new long[] {5, 15, 30, 60}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
