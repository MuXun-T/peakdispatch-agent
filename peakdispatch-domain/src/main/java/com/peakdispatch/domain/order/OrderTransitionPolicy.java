package com.peakdispatch.domain.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class OrderTransitionPolicy {
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            OrderStatus.CREATED, EnumSet.of(OrderStatus.WAITING_DISPATCH, OrderStatus.CANCELLED),
            OrderStatus.WAITING_DISPATCH, EnumSet.of(OrderStatus.OFFERED, OrderStatus.CANCELLED),
            OrderStatus.OFFERED, EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.WAITING_DISPATCH, OrderStatus.CANCELLED),
            OrderStatus.ACCEPTED, EnumSet.of(OrderStatus.PICKED_UP, OrderStatus.WAITING_DISPATCH, OrderStatus.CANCELLED),
            OrderStatus.PICKED_UP, EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
            OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.OPS_ESCALATED, EnumSet.of(OrderStatus.WAITING_DISPATCH, OrderStatus.CANCELLED));

    private OrderTransitionPolicy() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return from != null && to != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }
}
