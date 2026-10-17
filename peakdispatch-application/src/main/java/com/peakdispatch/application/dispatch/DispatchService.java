package com.peakdispatch.application.dispatch;

import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import com.peakdispatch.application.courier.CourierIndexEntry;
import com.peakdispatch.application.courier.CourierProjection;
import com.peakdispatch.domain.courier.CourierPresence;
import com.peakdispatch.domain.dispatch.DispatchCandidate;
import com.peakdispatch.domain.dispatch.DispatchOffer;
import com.peakdispatch.domain.dispatch.DispatchPolicy;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DispatchService {
    private static final Logger log = Logger.getLogger(DispatchService.class.getName());
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private final DispatchRepository dispatch;
    private final CourierRepository couriers;
    private final CourierProjection projection;
    private final OfferTokenProtector tokenProtector;
    private final DispatchPolicyProvider policies;
    private final RedisAvailability redisAvailability;
    private final OfferDelayQueue delayQueue;
    private final DispatchReadiness dispatchReadiness;
    private final Clock clock;
    private final boolean redisGateEnabled;
    private final long redisRecoveryDelaySeconds;
    private final PeakDispatchMetrics metrics;

    public DispatchService(DispatchRepository dispatch, CourierRepository couriers,
                           OfferTokenProtector tokenProtector, DispatchPolicyProvider policies,
                           RedisAvailability redisAvailability, Clock clock,
                           @Value("${peakdispatch.dispatch.redis-gate-enabled:true}") boolean redisGateEnabled,
                           @Value("${peakdispatch.dispatch.redis-recovery-delay-seconds:5}") long redisRecoveryDelaySeconds) {
        this(dispatch, couriers, tokenProtector, policies, redisAvailability, (CourierProjection) null, clock,
                null, null, redisGateEnabled, redisRecoveryDelaySeconds, null);
    }

    @Autowired
    public DispatchService(DispatchRepository dispatch, CourierRepository couriers,
                           OfferTokenProtector tokenProtector, DispatchPolicyProvider policies,
                           RedisAvailability redisAvailability, Optional<CourierProjection> projection, Clock clock,
                           Optional<OfferDelayQueue> delayQueue,
                           Optional<DispatchReadiness> dispatchReadiness,
                           @Value("${peakdispatch.dispatch.redis-gate-enabled:true}") boolean redisGateEnabled,
                           @Value("${peakdispatch.dispatch.redis-recovery-delay-seconds:5}") long redisRecoveryDelaySeconds,
                           PeakDispatchMetrics metrics) {
        this(dispatch, couriers, tokenProtector, policies, redisAvailability,
                projection.orElse(null), clock, delayQueue.orElse(null), dispatchReadiness.orElse(null),
                redisGateEnabled, redisRecoveryDelaySeconds, metrics);
    }

    public DispatchService(DispatchRepository dispatch, CourierRepository couriers,
                           OfferTokenProtector tokenProtector, DispatchPolicyProvider policies,
                           RedisAvailability redisAvailability, Optional<CourierProjection> projection, Clock clock,
                           @Value("${peakdispatch.dispatch.redis-gate-enabled:true}") boolean redisGateEnabled,
                           @Value("${peakdispatch.dispatch.redis-recovery-delay-seconds:5}") long redisRecoveryDelaySeconds) {
        this(dispatch, couriers, tokenProtector, policies, redisAvailability, projection, clock, Optional.empty(),
                Optional.empty(),
                redisGateEnabled, redisRecoveryDelaySeconds, null);
    }

    private DispatchService(DispatchRepository dispatch, CourierRepository couriers,
                            OfferTokenProtector tokenProtector, DispatchPolicyProvider policies,
                            RedisAvailability redisAvailability, CourierProjection projection, Clock clock,
                            OfferDelayQueue delayQueue,
                            DispatchReadiness dispatchReadiness,
                            boolean redisGateEnabled, long redisRecoveryDelaySeconds,
                            PeakDispatchMetrics metrics) {
        this.dispatch = dispatch;
        this.couriers = couriers;
        this.projection = projection;
        this.tokenProtector = tokenProtector;
        this.policies = policies;
        this.redisAvailability = redisAvailability;
        this.delayQueue = delayQueue;
        this.dispatchReadiness = dispatchReadiness;
        this.clock = clock;
        this.redisGateEnabled = redisGateEnabled;
        this.redisRecoveryDelaySeconds = redisRecoveryDelaySeconds;
        this.metrics = metrics;
    }

    @Transactional
    public void advanceCreatedOrders() {
        Instant now = Instant.now(clock);
        dispatch.findCreated(100).forEach(order -> dispatch.markWaitingDispatch(order.id(), order.version(), now));
    }

    @Transactional
    public void advanceCreatedOrder(UUID orderId) {
        dispatch.findCreatedOrder(orderId).ifPresent(order ->
                dispatch.markWaitingDispatch(order.id(), order.version(), Instant.now(clock)));
    }

    public void dispatchWaitingOrders() {
        for (DeliveryOrder order : dispatch.findWaitingDispatch(100)) {
            tryDispatch(order.id());
        }
    }

    public void expireOffers() {
        Instant now = Instant.now(clock);
        if (delayQueue != null) {
            try {
                for (OfferDelayQueue.DueOffer due : delayQueue.claimDue(100, now)) {
                    try {
                        dispatch.expireOffer(due.offerId(), now);
                        if (metrics != null) metrics.offer("timeout");
                        delayQueue.complete(due);
                    } catch (RuntimeException failure) {
                        delayQueue.release(due, now.plusSeconds(redisRecoveryDelaySeconds));
                        log.log(Level.WARNING, "Offer delay task deferred for " + due.offerId(), failure);
                    }
                }
            } catch (RuntimeException failure) {
                log.log(Level.WARNING, "Redis offer delay queue unavailable; DB expiry scan remains active", failure);
            }
        }
        dispatch.findExpiredOffers(now, 100).forEach(offer -> {
            dispatch.expireOffer(offer.id(), now);
            if (metrics != null) metrics.offer("timeout");
        });
    }

    @Transactional
    public DispatchDecision tryDispatch(UUID orderId) {
        Instant started = Instant.now(clock);
        DispatchDecision result = decideDispatch(orderId);
        if (metrics != null) metrics.dispatchDecision(Duration.between(started, Instant.now(clock)),
                result.candidates().size(), result.outcome());
        return result;
    }

    private DispatchDecision decideDispatch(UUID orderId) {
        Instant now = Instant.now(clock);
        DeliveryOrder order = dispatch.findWaitingOrder(orderId, now).orElse(null);
        if (order == null || order.status() != OrderStatus.WAITING_DISPATCH) {
            return new DispatchDecision(orderId, "STALE_ORDER", List.of(), null);
        }
        DispatchPolicyProvider.PolicySelection policySelection = policies.active(order.regionId());
        DispatchPolicy policy = policySelection.policy();
        if (redisGateEnabled && !redisAvailability.available()) {
            if (dispatchReadiness != null) dispatchReadiness.pause();
            dispatch.recordDispatchUnavailable(order, now, now.plusSeconds(redisRecoveryDelaySeconds), policySelection);
            return new DispatchDecision(orderId, "REDIS_UNAVAILABLE", List.of(), null);
        }
        if (dispatchReadiness != null && !dispatchReadiness.ready()) {
            dispatch.recordDispatchUnavailable(order, now, now.plusSeconds(redisRecoveryDelaySeconds), policySelection);
            return new DispatchDecision(orderId, "REDIS_RECOVERY", List.of(), null);
        }
        List<CourierPresence> snapshots;
        try {
            snapshots = candidateSnapshots(order, policy, now);
        } catch (RuntimeException failure) {
            dispatch.recordDispatchUnavailable(order, now, now.plusSeconds(redisRecoveryDelaySeconds), policySelection);
            return new DispatchDecision(orderId, "REDIS_UNAVAILABLE", List.of(), null);
        }
        List<DispatchCandidate> candidates = snapshots.stream()
                .filter(courier -> courier.isOnlineAt(now, policy.heartbeatTimeoutSeconds()))
                .filter(courier -> courier.latitude() != null && courier.longitude() != null)
                .filter(courier -> courier.committedCount() + courier.reservedCount() < courier.capacityLimit())
                .map(courier -> score(order, courier, now, policy))
                .filter(candidate -> candidate.distanceKm() <= policy.maxPickupDistanceKm()
                        && !candidate.estimatedDeliveryAt().isAfter(order.deadline()))
                .sorted(Comparator.comparingDouble(DispatchCandidate::score)
                        .thenComparing(DispatchCandidate::estimatedDeliveryAt)
                        .thenComparing(candidate -> candidate.courierId().toString()))
                .toList();
        for (DispatchCandidate candidate : candidates) {
            CourierPresence courier = couriers.find(candidate.courierId()).orElse(null);
            if (courier == null) {
                continue;
            }
            String token = UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
            UUID offerId = UUID.randomUUID();
            Instant expiresAt = now.plusSeconds(policy.offerTtlSeconds());
            String tokenHash = sha256(token);
            CourierProjection.Lease lease = null;
            if (projection != null) {
                lease = projection.reserve(courier, offerId, tokenHash,
                        policy.heartbeatTimeoutSeconds(), 15_000);
                if (lease == null) {
                    if (metrics != null) metrics.lease("rejected");
                    continue;
                }
                if (metrics != null) metrics.lease("success");
            }
            if (dispatch.createOffer(order, courier, candidate, offerId, tokenHash, tokenProtector.protect(token),
                    candidates, now, expiresAt, policySelection)) {
                if (metrics != null) metrics.offer("created");
                if (lease != null) {
                    registerLeaseCompletion(lease);
                }
                registerOfferDelay(offerId, expiresAt);
                return new DispatchDecision(orderId, "OFFER_CREATED", candidates, new OfferIssued(
                        offerId, order.id(), courier.courierId(), token, expiresAt, order.version() + 1));
            }
            if (metrics != null) metrics.dbCasConflict();
            if (lease != null) {
                projection.release(lease);
                if (metrics != null) metrics.lease("rollback");
            }
        }
        if (candidates.isEmpty()) {
            dispatch.recordNoCandidate(order, candidates, "NO_CANDIDATE", now, policySelection);
            return new DispatchDecision(orderId, "NO_CANDIDATE", candidates, null);
        }
        return new DispatchDecision(orderId, "CONTENDED", candidates, null);
    }

    private List<CourierPresence> candidateSnapshots(DeliveryOrder order, DispatchPolicy policy, Instant now) {
        if (projection == null) {
            return couriers.findOnlineInRegion(order.regionId());
        }
        List<CourierIndexEntry> indexed = projection.nearby(order.regionId(), order.pickupLat(), order.pickupLon(),
                policy.maxPickupDistanceKm(), policy.heartbeatTimeoutSeconds());
        Map<UUID, CourierIndexEntry> byCourier = indexed.stream()
                .collect(Collectors.toMap(CourierIndexEntry::courierId, Function.identity(), (first, ignored) -> first));
        return indexed.stream().map(entry -> couriers.find(entry.courierId()).orElse(null))
                .filter(courier -> courier != null && courier.isOnlineAt(now, policy.heartbeatTimeoutSeconds()))
                .filter(courier -> courier.regionId().equals(order.regionId()))
                .filter(courier -> courier.presenceGeneration() == byCourier.get(courier.courierId()).presenceGeneration())
                .filter(courier -> courier.sequence() == byCourier.get(courier.courierId()).sequence())
                .filter(courier -> courier.latitude() != null && courier.longitude() != null)
                .toList();
    }

    private void registerLeaseCompletion(CourierProjection.Lease lease) {
        Runnable committed = () -> couriers.find(lease.courierId()).ifPresent(snapshot -> {
            try {
                projection.confirm(lease, snapshot);
            } catch (RuntimeException failure) {
                log.log(Level.WARNING, "Redis lease confirmation deferred for offer " + lease.offerId(), failure);
            }
        });
        Runnable rolledBack = () -> {
            try {
                projection.release(lease);
                if (metrics != null) metrics.lease("rollback");
            } catch (RuntimeException failure) {
                log.log(Level.WARNING, "Redis provisional lease will expire for offer " + lease.offerId(), failure);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committed.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committed.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    rolledBack.run();
                }
            }
        });
    }

    private void registerOfferDelay(UUID offerId, Instant expiresAt) {
        if (delayQueue == null) return;
        Runnable schedule = () -> {
            try {
                delayQueue.schedule(offerId, expiresAt);
            } catch (RuntimeException failure) {
                log.log(Level.WARNING, "Redis offer delay scheduling deferred for " + offerId, failure);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            schedule.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                schedule.run();
            }
        });
    }

    @Transactional(noRollbackFor = DispatchOfferConflictException.class)
    public DispatchRepository.OfferCommandResult accept(UUID courierId, UUID offerId, String token,
                                                        long expectedOrderVersion) {
        return dispatch.respondToOffer(courierId, offerId, sha256(token), expectedOrderVersion,
                true, null, Instant.now(clock));
    }

    @Transactional
    public DispatchRepository.OfferCommandResult reject(UUID courierId, UUID offerId, String token,
                                                        String reason) {
        DispatchOffer offer = dispatch.findOffer(offerId)
                .orElseThrow(() -> new DispatchOfferNotFoundException(offerId));
        return dispatch.respondToOffer(courierId, offerId, sha256(token), offer.orderVersion(),
                false, reason, Instant.now(clock));
    }

    @Transactional
    public DeliveryOrder pickup(UUID courierId, UUID orderId, long expectedVersion, UUID eventId) {
        return dispatch.transitionAssignedOrder(courierId, orderId, expectedVersion,
                OrderStatus.ACCEPTED, OrderStatus.PICKED_UP, eventId, Instant.now(clock));
    }

    @Transactional
    public DeliveryOrder deliver(UUID courierId, UUID orderId, long expectedVersion, UUID eventId) {
        return dispatch.transitionAssignedOrder(courierId, orderId, expectedVersion,
                OrderStatus.PICKED_UP, OrderStatus.DELIVERED, eventId, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public List<CourierOffer> activeOffers(UUID courierId) {
        return dispatch.findActiveOffersForCourier(courierId, Instant.now(clock)).stream()
                .map(offer -> new CourierOffer(offer.id(), offer.orderId(), offer.expiresAt(),
                        offer.orderVersion(), tokenProtector.reveal(offer.tokenCiphertext())))
                .toList();
    }

    private DispatchCandidate score(DeliveryOrder order, CourierPresence courier, Instant now,
                                    DispatchPolicy policy) {
        double distance = distanceKm(courier.latitude(), courier.longitude(), order.pickupLat(), order.pickupLon()) * 1.3;
        long pickupSeconds = Math.round(distance / 20.0 * 3600.0);
        Instant arrivalAtMerchant = now.plusSeconds(pickupSeconds);
        Instant pickupAt = arrivalAtMerchant.isBefore(order.prepEta()) ? order.prepEta() : arrivalAtMerchant;
        Instant deliveryAt = pickupAt.plusSeconds(Math.round(distanceKm(order.pickupLat(), order.pickupLon(),
                order.dropoffLat(), order.dropoffLon()) * 1.3 / 20.0 * 3600.0) + 120);
        double etaNorm = Math.min(1.0, pickupSeconds / 3600.0);
        double waitNorm = Math.min(1.0, Math.max(0, order.prepEta().getEpochSecond()
                - arrivalAtMerchant.getEpochSecond()) / 1800.0);
        double totalSlack = Math.max(1.0, order.deadline().getEpochSecond() - now.getEpochSecond());
        double remainingSlack = Math.max(0, order.deadline().getEpochSecond() - deliveryAt.getEpochSecond());
        double urgencyNorm = 1.0 - Math.min(1.0, remainingSlack / totalSlack);
        double loadRatio = (courier.committedCount() + courier.reservedCount()) / (double) courier.capacityLimit();
        double score = policy.etaWeight() * etaNorm + policy.loadWeight() * loadRatio
                + policy.urgencyWeight() * urgencyNorm + waitNorm * 0.05;
        return new DispatchCandidate(courier.courierId(), score, deliveryAt, distance, loadRatio,
                "nearest feasible courier with available capacity");
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record OfferIssued(UUID offerId, UUID orderId, UUID courierId, String offerToken,
                              Instant expiresAt, long orderVersion) {
    }

    public record DispatchDecision(UUID orderId, String outcome, List<DispatchCandidate> candidates,
                                   OfferIssued offer) {
    }

    public record CourierOffer(UUID offerId, UUID orderId, Instant expiresAt, long orderVersion,
                               String offerToken) {
    }
}
