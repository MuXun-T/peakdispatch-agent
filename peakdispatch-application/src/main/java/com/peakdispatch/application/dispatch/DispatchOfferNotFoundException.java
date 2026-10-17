package com.peakdispatch.application.dispatch;

import java.util.UUID;

public class DispatchOfferNotFoundException extends RuntimeException {
    public DispatchOfferNotFoundException(UUID id) {
        super("dispatch offer not found: " + id);
    }
}
