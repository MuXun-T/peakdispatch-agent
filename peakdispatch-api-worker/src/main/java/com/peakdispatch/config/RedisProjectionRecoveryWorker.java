package com.peakdispatch.config;

import com.peakdispatch.application.courier.CourierProjection;
import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.dispatch.RedisAvailability;
import com.peakdispatch.application.dispatch.DispatchReadiness;
import com.peakdispatch.domain.courier.CourierPresence;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded fallback that repairs derived Redis state from MySQL snapshots. */
@Component
@Profile("!test")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'API'")
public class RedisProjectionRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(RedisProjectionRecoveryWorker.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_ROWS_PER_RUN = 2_000;

    private final CourierRepository couriers;
    private final CourierProjection projection;
    private final RedisAvailability redisAvailability;
    private final DispatchReadiness dispatchReadiness;
    private UUID recoveryCursor;

    public RedisProjectionRecoveryWorker(CourierRepository couriers, CourierProjection projection,
                                         RedisAvailability redisAvailability, DispatchReadiness dispatchReadiness) {
        this.couriers = couriers;
        this.projection = projection;
        this.redisAvailability = redisAvailability;
        this.dispatchReadiness = dispatchReadiness;
    }

    @Scheduled(fixedDelayString = "${peakdispatch.worker.redis-reconcile-delay-ms:30000}")
    public void reconcileOnlineCouriers() {
        if (!redisAvailability.available()) {
            dispatchReadiness.pause();
            return;
        }
        if (dispatchReadiness.ready() && recoveryCursor == null) return;
        dispatchReadiness.pause();
        int processed = 0;
        while (processed < MAX_ROWS_PER_RUN) {
            int pageSize = Math.min(PAGE_SIZE, MAX_ROWS_PER_RUN - processed);
            List<CourierPresence> page = couriers.findOnlineAfter(recoveryCursor, pageSize);
            if (page.isEmpty()) {
                recoveryCursor = null;
                dispatchReadiness.resume();
                return;
            }
            for (CourierPresence courier : page) {
                try {
                    projection.reconcile(courier);
                    recoveryCursor = courier.courierId();
                } catch (RuntimeException failure) {
                    log.warn("Redis projection reconciliation deferred for courier {} ({})",
                            courier.courierId(), failure.getClass().getSimpleName());
                    return;
                }
            }
            processed += page.size();
            if (page.size() < pageSize) {
                recoveryCursor = null;
                dispatchReadiness.resume();
                return;
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildBeforeDispatchResumes() {
        recoveryCursor = null;
        dispatchReadiness.pause();
        reconcileOnlineCouriers();
    }
}
