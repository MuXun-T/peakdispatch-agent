package com.peakdispatch.application.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.peakdispatch.domain.order.OrderStatus;
import com.peakdispatch.domain.courier.CourierOnlineState;

public interface OperationsRepository {
    DashboardSnapshot dashboard(String regionId, Instant from, Instant to, Instant asOf);

    String orderRegion(UUID orderId);

    String courierRegion(UUID courierId);

    List<TimelineEntry> orderTimeline(UUID orderId);

    List<DispatchAttemptEvidence> dispatchEvidence(UUID orderId);

    OrderPage searchOrders(String regionId, OrderStatus status, UUID orderId, Instant createdFrom,
                           Instant createdTo, int page, int size, Instant asOf);

    CourierPage searchCouriers(String regionId, CourierOnlineState onlineState, UUID courierId,
                               Long presenceGeneration, int page, int size, Instant asOf);

    void audit(String actorId, String action, String targetType, String targetId, String decision,
               String details, String traceId, Instant occurredAt);

    record DashboardSnapshot(String regionId, Instant from, Instant to, Instant asOf,
                             long orders, long waitingDispatch, long offered, long accepted,
                             long pickedUp, long delivered, long cancelled, long activeOffers,
                             long offersCreated, long offerAccepted, long offerRejected,
                             long offerTimedOut, long dispatchAttempts, long dispatchFailures,
                             long escalations, long onlineCouriers, long capacityLimit,
                             long committedCapacity, long reservedCapacity) {
        public long availableCapacity() {
            return Math.max(0, capacityLimit - committedCapacity - reservedCapacity);
        }
    }

    record TimelineEntry(Instant occurredAt, String type, String source, String actorId,
                         Long version, String details, String evidenceId) {
    }

    record DispatchAttemptEvidence(String evidenceId, UUID attemptId, int roundNo, long orderVersion,
                                   String outcome, int candidateCount, UUID selectedCourierId,
                                   Long policyVersion, String exclusionSummary, Instant startedAt,
                                   Instant finishedAt, List<CandidateDecisionEvidence> candidates) {
        public DispatchAttemptEvidence {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    record CandidateDecisionEvidence(String evidenceId, UUID courierId, boolean eligible,
                                     String reasonCode, Integer rankNo, Double score,
                                     String featureSnapshot) {
    }

    record OrderPage(List<OrderRow> items, int page, int size, long totalElements,
                     Instant asOf, String source) {
    }

    record OrderRow(UUID orderId, UUID merchantId, String regionId, OrderStatus status, long version,
                    Instant prepEta, Instant deadline, Instant createdAt, Instant updatedAt,
                    String dispatchPhase, UUID activeCourierId) {
    }

    record CourierPage(List<CourierRow> items, int page, int size, long totalElements,
                       Instant asOf, String source) {
    }

    record CourierRow(UUID courierId, String regionId, CourierOnlineState onlineState,
                      long presenceGeneration, Instant lastHeartbeatAt, int capacityLimit,
                      int committedCount, int reservedCount, int availableCapacity, long capacityVersion) {
    }
}
