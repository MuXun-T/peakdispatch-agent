package com.peakdispatch.application.order;

import java.util.UUID;

public class PreparationEventConflictException extends RuntimeException {
    public PreparationEventConflictException(UUID eventId) {
        super("preparation eventId was already used with a different command: " + eventId);
    }
}
