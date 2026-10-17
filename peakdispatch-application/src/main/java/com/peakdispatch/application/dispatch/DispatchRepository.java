package com.peakdispatch.application.dispatch;

import com.peakdispatch.domain.courier.CourierPresence;
import com.peakdispatch.domain.dispatch.DispatchCandidate;
import com.peakdispatch.domain.dispatch.DispatchOffer;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispatchRepository {
    List<DeliveryOrder> findCreated(int limit);
    Optional<DeliveryOrder> findCreatedOrder(UUID orderId);
    boolean markWaitingDispatch(UUID orderId, long expectedVersion, Instant now);
    List<DeliveryOrder> findWaitingDispatch(int limit);
    Optional<DeliveryOrder> findWaitingOrder(UUID orderId, Instant now);
    boolean createOffer(DeliveryOrder order, CourierPresence courier, DispatchCandidate candidate,
                        UUID offerId, String tokenHash, String tokenCiphertext,
                        List<DispatchCandidate> rankedCandidates, Instant now, Instant expiresAt,
                        DispatchPolicyProvider.PolicySelection policy);
    void recordNoCandidate(DeliveryOrder order, List<DispatchCandidate> candidates, String outcome,
                           Instant attemptedAt, DispatchPolicyProvider.PolicySelection policy);
    void recordDispatchUnavailable(DeliveryOrder order, Instant attemptedAt, Instant retryAt,
                                   DispatchPolicyProvider.PolicySelection policy);
    void invalidateStaleCourierOffers(UUID courierId, long currentGeneration, Instant now, String reason);
    void releaseOrderResources(UUID orderId, String reason, Instant now);
    void reassessPreparationOffer(UUID orderId, Instant prepEta, boolean ready, Instant now);
    Optional<DispatchOffer> findOffer(UUID offerId);
    Optional<DispatchOffer> findActiveOffer(UUID orderId);
    List<DispatchOffer> findActiveOffersForCourier(UUID courierId, Instant now);
    OfferCommandResult respondToOffer(UUID courierId, UUID offerId, String tokenHash, long expectedOrderVersion,
                                      boolean accept, String reason, Instant now);
    DeliveryOrder transitionAssignedOrder(UUID courierId, UUID orderId, long expectedVersion,
                                          OrderStatus expectedStatus, OrderStatus target, UUID eventId, Instant now);
    List<DispatchOffer> findExpiredOffers(Instant now, int limit);
    OfferCommandResult expireOffer(UUID offerId, Instant now);

    record OfferCommandResult(DeliveryOrder order, DispatchOffer offer, boolean changed) {
    }
}
