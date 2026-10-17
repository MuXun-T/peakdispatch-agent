package com.peakdispatch.domain.dispatch;

import java.time.Instant;
import java.util.UUID;

public record DispatchOffer(
        UUID id,
        UUID orderId,
        UUID courierId,
        long courierPresenceGeneration,
        int round,
        String tokenHash,
        String tokenCiphertext,
        DispatchOfferStatus status,
        Instant expiresAt,
        long orderVersion,
        Instant createdAt,
        Instant updatedAt,
        UUID policyId,
        long policyVersion,
        String policySnapshot) {

    public boolean activeAt(Instant now) {
        return status == DispatchOfferStatus.OFFERED && expiresAt.isAfter(now);
    }
}
