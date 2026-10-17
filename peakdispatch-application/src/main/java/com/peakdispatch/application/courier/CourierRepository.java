package com.peakdispatch.application.courier;

import com.peakdispatch.domain.courier.CourierPresence;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourierRepository {
    Optional<CourierPresence> find(UUID courierId);
    boolean ownedBy(UUID courierId, String username);
    List<CourierPresence> findOnlineInRegion(String regionId);
    List<CourierPresence> findOnlineAfter(UUID lastCourierId, int limit);
    CourierPresence setOnline(UUID courierId, String regionId, Instant now);
    CourierPresence setOffline(UUID courierId, Instant now);
    CourierPresence heartbeat(UUID courierId, String regionId, long generation, long sequence,
                              double latitude, double longitude, Instant now);
    void invalidateStaleOffers(UUID courierId, long currentGeneration, Instant now, String reason);
}
