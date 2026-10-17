package com.peakdispatch.application.order;

import com.peakdispatch.domain.order.OrderStatus;
import java.util.UUID;

public class OrderVersionConflictException extends RuntimeException {
    private final UUID orderId;
    private final long currentVersion;
    private final OrderStatus currentStatus;

    public OrderVersionConflictException(UUID orderId, long currentVersion, OrderStatus currentStatus) {
        super("order version or status no longer matches");
        this.orderId = orderId;
        this.currentVersion = currentVersion;
        this.currentStatus = currentStatus;
    }

    public UUID orderId() { return orderId; }
    public long currentVersion() { return currentVersion; }
    public OrderStatus currentStatus() { return currentStatus; }
}
