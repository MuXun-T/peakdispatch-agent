package com.peakdispatch.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderTransitionPolicyTest {

    @Test
    void transitionPolicy_allowsOnlyDocumentedOrderLifecycleEdges() {
        assertThat(OrderTransitionPolicy.canTransition(OrderStatus.CREATED, OrderStatus.WAITING_DISPATCH)).isTrue();
        assertThat(OrderTransitionPolicy.canTransition(OrderStatus.OFFERED, OrderStatus.ACCEPTED)).isTrue();
        assertThat(OrderTransitionPolicy.canTransition(OrderStatus.DELIVERED, OrderStatus.WAITING_DISPATCH)).isFalse();
        assertThat(OrderTransitionPolicy.canTransition(OrderStatus.CANCELLED, OrderStatus.ACCEPTED)).isFalse();
    }
}
