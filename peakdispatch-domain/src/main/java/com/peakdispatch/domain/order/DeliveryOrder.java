package com.peakdispatch.domain.order;

import java.time.Instant;
import java.util.UUID;

public record DeliveryOrder(
        UUID id,
        UUID merchantId,
        String regionId,
        double pickupLat,
        double pickupLon,
        double dropoffLat,
        double dropoffLon,
        Instant prepEta,
        Instant deadline,
        boolean preparationReady,
        OrderStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public DeliveryOrder {
        if (id == null || merchantId == null || regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("order identity and region are required");
        }
        if (prepEta == null || deadline == null || !prepEta.isBefore(deadline)) {
            throw new IllegalArgumentException("prepEta must be before deadline");
        }
        if (version < 0 || status == null) {
            throw new IllegalArgumentException("invalid order version or status");
        }
        if (!coordinate(pickupLat, pickupLon) || !coordinate(dropoffLat, dropoffLon)) {
            throw new IllegalArgumentException("coordinates are out of range");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("order timestamps are required");
        }
    }

    public DeliveryOrder(UUID id, UUID merchantId, String regionId, double pickupLat, double pickupLon,
                         double dropoffLat, double dropoffLon, Instant prepEta, Instant deadline,
                         OrderStatus status, long version, Instant createdAt, Instant updatedAt) {
        this(id, merchantId, regionId, pickupLat, pickupLon, dropoffLat, dropoffLon,
                prepEta, deadline, false, status, version, createdAt, updatedAt);
    }

    public DeliveryOrder transitionTo(OrderStatus target, Instant now) {
        if (!OrderTransitionPolicy.canTransition(status, target)) {
            throw new InvalidOrderTransitionException(status, target);
        }
        return new DeliveryOrder(id, merchantId, regionId, pickupLat, pickupLon, dropoffLat, dropoffLon,
                prepEta, deadline, preparationReady, target, version + 1, createdAt, now);
    }

    public DeliveryOrder updatePreparation(Instant nextPrepEta, boolean ready, Instant now) {
        if (nextPrepEta == null || !nextPrepEta.isBefore(deadline)) {
            throw new IllegalArgumentException("prepEta must be before deadline");
        }
        return new DeliveryOrder(id, merchantId, regionId, pickupLat, pickupLon, dropoffLat, dropoffLon,
                nextPrepEta, deadline, ready, status, version + 1, createdAt, now);
    }

    private static boolean coordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }
}
