package com.peakdispatch.application.courier;

import java.util.UUID;

public class CourierSessionConflictException extends RuntimeException {
    private final UUID courierId;

    public CourierSessionConflictException(UUID courierId, String message) {
        super(message);
        this.courierId = courierId;
    }

    public UUID courierId() {
        return courierId;
    }
}
