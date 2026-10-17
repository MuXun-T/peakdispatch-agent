package com.peakdispatch.application.order;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID id) {
        super("order not found: " + id);
    }
}
