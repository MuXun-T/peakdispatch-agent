package com.peakdispatch.application.order;

import com.peakdispatch.domain.order.DeliveryOrder;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import com.peakdispatch.domain.order.OrderStatus;

public interface OrderRepository {
    DeliveryOrder insert(DeliveryOrder order);
    Optional<DeliveryOrder> findById(UUID id);
    boolean merchantCanAccept(UUID merchantId, String regionId);
    boolean merchantOwnedBy(UUID merchantId, String username);
    boolean merchantOwnsOrder(UUID orderId, String username);
    boolean courierWasAssigned(UUID orderId, String username);
    boolean orderInRegion(UUID orderId, String regionId);
    boolean orderInRegions(UUID orderId, Set<String> regionIds);
    boolean transitionStatus(UUID orderId, long expectedVersion, OrderStatus expectedStatus,
                             OrderStatus targetStatus, Instant updatedAt);
    boolean updatePreparation(UUID orderId, long expectedVersion, Instant prepEta, boolean ready,
                              UUID eventId, String actorId, Instant updatedAt);
    boolean cancel(UUID orderId, long expectedVersion, String reason, String actorId, Instant updatedAt);
    boolean approveReschedule(UUID orderId, long expectedVersion, Instant prepEta, Instant deadline,
                              String reason, String actorId, Instant updatedAt);
    boolean reassign(UUID orderId, long expectedVersion, String reason, String actorId, Instant updatedAt);
    Optional<PreparationEvent> findPreparationEvent(UUID eventId);

    record PreparationEvent(UUID orderId, String actorId, Instant prepEta, boolean ready) {
    }
}
