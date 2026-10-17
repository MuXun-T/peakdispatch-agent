package com.peakdispatch.application.courier;

import java.util.UUID;

public class CourierNotFoundException extends RuntimeException {
    public CourierNotFoundException(UUID id) {
        super("courier not found: " + id);
    }
}
