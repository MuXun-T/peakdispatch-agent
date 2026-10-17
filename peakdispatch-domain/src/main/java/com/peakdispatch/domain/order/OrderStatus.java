package com.peakdispatch.domain.order;

public enum OrderStatus {
    CREATED,
    WAITING_DISPATCH,
    OFFERED,
    ACCEPTED,
    PICKED_UP,
    DELIVERED,
    CANCELLED,
    OPS_ESCALATED
}
