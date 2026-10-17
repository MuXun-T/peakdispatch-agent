package com.peakdispatch.application.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.order.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
    private static final Duration MAX_WINDOW = Duration.ofDays(31);
    private final OperationsRepository repository;
    private final Clock clock;

    public OperationsService(OperationsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public OperationsRepository.DashboardSnapshot dashboard(String regionId, Instant from, Instant to,
                                                             String actorId, String traceId) {
        validateWindow(regionId, from, to);
        Instant asOf = Instant.now(clock);
        var result = repository.dashboard(regionId, from, to, asOf);
        repository.audit(actorId, "OPS_DASHBOARD_READ", "REGION", regionId, "ALLOWED",
                "from=" + from + ";to=" + to, traceId, asOf);
        return result;
    }

    @Transactional(readOnly = true)
    public String orderRegion(UUID orderId) {
        return repository.orderRegion(orderId);
    }

    @Transactional(readOnly = true)
    public String courierRegion(UUID courierId) {
        return repository.courierRegion(courierId);
    }

    @Transactional
    public List<OperationsRepository.TimelineEntry> orderTimeline(UUID orderId, String actorId, String traceId) {
        String region = repository.orderRegion(orderId);
        if (region == null) return List.of();
        Instant now = Instant.now(clock);
        List<OperationsRepository.TimelineEntry> entries = repository.orderTimeline(orderId);
        repository.audit(actorId, "OPS_ORDER_TIMELINE_READ", "ORDER", orderId.toString(), "ALLOWED",
                "region=" + region + ";entryCount=" + entries.size(), traceId, now);
        return entries;
    }

    @Transactional
    public List<OperationsRepository.DispatchAttemptEvidence> dispatchEvidence(UUID orderId,
                                                                                 String actorId,
                                                                                 String traceId) {
        String region = repository.orderRegion(orderId);
        if (region == null) return List.of();
        Instant now = Instant.now(clock);
        List<OperationsRepository.DispatchAttemptEvidence> evidence = repository.dispatchEvidence(orderId);
        repository.audit(actorId, "OPS_ORDER_DISPATCH_EVIDENCE_READ", "ORDER", orderId.toString(), "ALLOWED",
                "region=" + region + ";attemptCount=" + evidence.size(), traceId, now);
        return evidence;
    }

    @Transactional
    public OperationsRepository.OrderPage searchOrders(String regionId, OrderStatus status, UUID orderId,
                                                         Instant createdFrom, Instant createdTo, int page, int size,
                                                         String actorId, String traceId) {
        validatePage(page, size);
        validateRegion(regionId);
        if (createdFrom != null && createdTo != null
                && (!createdFrom.isBefore(createdTo)
                || Duration.between(createdFrom, createdTo).compareTo(MAX_WINDOW) > 0)) {
            throw new IllegalArgumentException("created time window must be valid and at most 31 days");
        }
        Instant asOf = Instant.now(clock);
        OperationsRepository.OrderPage result = repository.searchOrders(regionId, status, orderId,
                createdFrom, createdTo, page, size, asOf);
        repository.audit(actorId, "OPS_ORDERS_READ", "REGION", regionId, "ALLOWED",
                "status=" + status + ";orderId=" + orderId + ";page=" + page + ";size=" + size
                        + ";total=" + result.totalElements(), traceId, asOf);
        return result;
    }

    @Transactional
    public OperationsRepository.CourierPage searchCouriers(String regionId, CourierOnlineState onlineState,
                                                             UUID courierId, Long presenceGeneration, int page,
                                                             int size, String actorId, String traceId) {
        validatePage(page, size);
        validateRegion(regionId);
        if (presenceGeneration != null && presenceGeneration < 0) {
            throw new IllegalArgumentException("presenceGeneration must be non-negative");
        }
        Instant asOf = Instant.now(clock);
        OperationsRepository.CourierPage result = repository.searchCouriers(regionId, onlineState, courierId,
                presenceGeneration, page, size, asOf);
        repository.audit(actorId, "OPS_COURIERS_READ", "REGION", regionId, "ALLOWED",
                "onlineState=" + onlineState + ";courierId=" + courierId + ";presenceGeneration="
                        + presenceGeneration + ";page=" + page + ";size=" + size
                        + ";total=" + result.totalElements(), traceId, asOf);
        return result;
    }

    @Transactional
    public void recordAudit(String actorId, String action, String targetType, String targetId,
                            String decision, String details, String traceId) {
        repository.audit(actorId, action, targetType, targetId, decision, details,
                traceId, Instant.now(clock));
    }

    private static void validateWindow(String regionId, Instant from, Instant to) {
        if (regionId == null || !regionId.matches("[A-Za-z0-9_-]{1,64}") || from == null || to == null
                || !from.isBefore(to) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("regionId and a valid time window of at most 31 days are required");
        }
    }

    private static void validateRegion(String regionId) {
        if (regionId == null || !regionId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("regionId is invalid");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
    }
}
