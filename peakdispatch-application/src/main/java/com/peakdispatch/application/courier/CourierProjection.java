package com.peakdispatch.application.courier;

import com.peakdispatch.domain.courier.CourierPresence;
import java.util.List;
import java.util.UUID;

/** Redis-derived presence, location, capacity and short-lived dispatch lease operations. */
public interface CourierProjection {
    void projectSession(CourierPresence presence);
    default void projectSession(CourierPresence presence, String previousRegionId) {
        projectSession(presence);
    }
    void projectHeartbeat(CourierPresence presence);
    void removeSession(CourierPresence presence);
    default void removeSession(CourierPresence presence, String previousRegionId) {
        removeSession(presence);
    }
    void projectCapacity(CourierPresence presence, UUID confirmedOfferId);
    List<CourierIndexEntry> nearby(String regionId, double latitude, double longitude,
                                   double radiusKm, long heartbeatTimeoutSeconds);
    Lease reserve(CourierPresence courier, UUID offerId, String tokenHash, long heartbeatTimeoutSeconds,
                  long leaseTtlMillis);
    void release(Lease lease);
    void confirm(Lease lease, CourierPresence committedSnapshot);
    void reconcile(CourierPresence databaseSnapshot);

    record Lease(UUID courierId, UUID offerId, String tokenHash, long expiresAtMillis) {
    }
}
