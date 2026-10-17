package com.peakdispatch.application.courier;

import com.peakdispatch.domain.courier.CourierPresence;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourierPresenceService {
    private static final Logger log = Logger.getLogger(CourierPresenceService.class.getName());

    private final CourierRepository couriers;
    private final CourierProjection projection;
    private final Clock clock;

    public CourierPresenceService(CourierRepository couriers, Optional<CourierProjection> projection, Clock clock) {
        this.couriers = couriers;
        this.projection = projection.orElse(null);
        this.clock = clock;
    }

    @Transactional
    public CourierPresence setOnline(UUID courierId, String regionId) {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("regionId is required to go online");
        }
        Instant now = Instant.now(clock);
        CourierPresence result = couriers.setOnline(courierId, regionId, now);
        couriers.invalidateStaleOffers(courierId, result.presenceGeneration(), now, "COURIER_SESSION_CHANGED");
        if (projection != null) afterCommit(() -> projectSafely("session", result,
                () -> projection.projectSession(result)));
        return result;
    }

    @Transactional(readOnly = true)
    public boolean ownedBy(UUID courierId, String username) {
        return couriers.ownedBy(courierId, username);
    }

    @Transactional
    public CourierPresence setOffline(UUID courierId) {
        Instant now = Instant.now(clock);
        CourierPresence result = couriers.setOffline(courierId, now);
        couriers.invalidateStaleOffers(courierId, -1, now, "COURIER_OFFLINE");
        if (projection != null) afterCommit(() -> projectSafely("offline session", result,
                () -> projection.removeSession(result)));
        return result;
    }

    @Transactional
    public CourierPresence heartbeat(UUID courierId, String regionId, long generation, long sequence,
                                     double latitude, double longitude) {
        if (sequence <= 0 || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new IllegalArgumentException("invalid heartbeat sequence or coordinates");
        }
        CourierPresence result = couriers.heartbeat(courierId, regionId, generation, sequence,
                latitude, longitude, Instant.now(clock));
        if (projection != null) afterCommit(() -> projectSafely("heartbeat", result,
                () -> projection.projectHeartbeat(result)));
        return result;
    }

    private void projectSafely(String operation, CourierPresence presence, Runnable projectionAction) {
        try {
            projectionAction.run();
        } catch (RuntimeException failure) {
            log.log(Level.WARNING, "Redis courier " + operation + " projection deferred for courier "
                    + presence.courierId() + " (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
