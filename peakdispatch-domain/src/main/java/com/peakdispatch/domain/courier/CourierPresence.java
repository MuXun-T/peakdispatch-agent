package com.peakdispatch.domain.courier;

import java.time.Instant;
import java.util.UUID;

/** MySQL-backed presence snapshot. Redis may project this snapshot, but is not the source of truth. */
public record CourierPresence(
        UUID courierId,
        String regionId,
        CourierOnlineState onlineState,
        long presenceGeneration,
        long sequence,
        Double latitude,
        Double longitude,
        Instant lastHeartbeatAt,
        int capacityLimit,
        int committedCount,
        int reservedCount,
        long capacityVersion,
        Instant updatedAt) {

    public CourierPresence {
        if (courierId == null || onlineState == null || presenceGeneration < 0 || sequence < 0) {
            throw new IllegalArgumentException("invalid courier presence");
        }
        if (capacityLimit <= 0 || committedCount < 0 || reservedCount < 0
                || committedCount + reservedCount > capacityLimit || capacityVersion < 0) {
            throw new IllegalArgumentException("invalid courier capacity");
        }
        if (latitude != null && (latitude.isNaN() || latitude < -90 || latitude > 90)
                || longitude != null && (longitude.isNaN() || longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("invalid courier coordinates");
        }
    }

    public boolean isOnlineAt(Instant now, long heartbeatTimeoutSeconds) {
        return onlineState == CourierOnlineState.ONLINE && lastHeartbeatAt != null
                && lastHeartbeatAt.plusSeconds(heartbeatTimeoutSeconds).isAfter(now);
    }
}
