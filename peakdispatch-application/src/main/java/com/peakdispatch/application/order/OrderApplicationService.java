package com.peakdispatch.application.order;

import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderApplicationService {
    private final OrderRepository orders;
    private final Clock clock;

    public OrderApplicationService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean merchantOwnedBy(UUID merchantId, String username) {
        return orders.merchantOwnedBy(merchantId, username);
    }

    @Transactional(readOnly = true)
    public boolean merchantOwnsOrder(UUID orderId, String username) {
        return orders.merchantOwnsOrder(orderId, username);
    }

    @Transactional(readOnly = true)
    public boolean courierWasAssigned(UUID orderId, String username) {
        return orders.courierWasAssigned(orderId, username);
    }

    @Transactional(readOnly = true)
    public boolean orderInRegion(UUID orderId, String regionId) {
        return orders.orderInRegion(orderId, regionId);
    }

    @Transactional(readOnly = true)
    public boolean orderInRegions(UUID orderId, Set<String> regionIds) {
        return orders.orderInRegions(orderId, regionIds);
    }

    @Transactional
    public DeliveryOrder create(CreateOrderCommand command) {
        if (!orders.merchantCanAccept(command.merchantId(), command.regionId())) {
            throw new MerchantUnavailableException(command.merchantId(), command.regionId());
        }
        Instant now = Instant.now(clock);
        DeliveryOrder order = new DeliveryOrder(
                UUID.randomUUID(), command.merchantId(), command.regionId(),
                command.pickupLat(), command.pickupLon(), command.dropoffLat(), command.dropoffLon(),
                command.prepEta(), command.deadline(),
                com.peakdispatch.domain.order.OrderStatus.CREATED, 1, now, now);
        return orders.insert(order);
    }

    @Transactional
    public DeliveryOrder transition(UUID orderId, long expectedVersion,
                                    com.peakdispatch.domain.order.OrderStatus expectedStatus,
                                    com.peakdispatch.domain.order.OrderStatus targetStatus) {
        DeliveryOrder current = get(orderId);
        if (current.version() != expectedVersion || current.status() != expectedStatus) {
            throw new OrderVersionConflictException(orderId, current.version(), current.status());
        }
        Instant now = Instant.now(clock);
        DeliveryOrder next = current.transitionTo(targetStatus, now);
        if (!orders.transitionStatus(orderId, expectedVersion, expectedStatus, targetStatus, now)) {
            DeliveryOrder latest = get(orderId);
            throw new OrderVersionConflictException(orderId, latest.version(), latest.status());
        }
        return next;
    }

    @Transactional
    public DeliveryOrder updatePreparation(UUID merchantId, UUID orderId, long expectedVersion,
                                            Instant prepEta, boolean ready, UUID eventId, String actorId) {
        DeliveryOrder current = get(orderId);
        if (!current.merchantId().equals(merchantId)) {
            throw new MerchantOwnershipException(merchantId, orderId);
        }
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        OrderRepository.PreparationEvent priorEvent = orders.findPreparationEvent(eventId).orElse(null);
        if (priorEvent != null) {
            if (!priorEvent.orderId().equals(orderId) || !Objects.equals(priorEvent.actorId(), actorId)
                    || !Objects.equals(priorEvent.prepEta(), prepEta) || priorEvent.ready() != ready) {
                throw new PreparationEventConflictException(eventId);
            }
            return current;
        }
        if (current.version() != expectedVersion) {
            throw new OrderVersionConflictException(orderId, current.version(), current.status());
        }
        DeliveryOrder next = current.updatePreparation(prepEta, ready, Instant.now(clock));
        if (!orders.updatePreparation(orderId, expectedVersion, prepEta, ready, eventId, actorId,
                next.updatedAt())) {
            OrderRepository.PreparationEvent racedEvent = orders.findPreparationEvent(eventId).orElse(null);
            if (racedEvent != null) {
                if (racedEvent.orderId().equals(orderId) && Objects.equals(racedEvent.actorId(), actorId)
                        && Objects.equals(racedEvent.prepEta(), prepEta) && racedEvent.ready() == ready) {
                    return get(orderId);
                }
                throw new PreparationEventConflictException(eventId);
            }
            DeliveryOrder latest = get(orderId);
            throw new OrderVersionConflictException(orderId, latest.version(), latest.status());
        }
        // Reassessment may advance the order once more when the active offer is invalidated.
        return get(orderId);
    }

    @Transactional
    public DeliveryOrder cancel(UUID orderId, long expectedVersion, String reason, String actorId) {
        validateReason(reason, actorId);
        DeliveryOrder current = get(orderId);
        // Cancellation is terminal and therefore safe to replay with the original version.
        if (current.status() == OrderStatus.CANCELLED) {
            return current;
        }
        if (current.status() == OrderStatus.DELIVERED
                || !com.peakdispatch.domain.order.OrderTransitionPolicy.canTransition(
                        current.status(), OrderStatus.CANCELLED)) {
            throw new com.peakdispatch.domain.order.InvalidOrderTransitionException(current.status(),
                    OrderStatus.CANCELLED);
        }
        if (current.version() != expectedVersion) {
            throw new OrderVersionConflictException(orderId, current.version(), current.status());
        }
        Instant now = Instant.now(clock);
        if (!orders.cancel(orderId, expectedVersion, reason, actorId, now)) {
            DeliveryOrder latest = get(orderId);
            if (latest.status() == OrderStatus.CANCELLED) return latest;
            throw new OrderVersionConflictException(orderId, latest.version(), latest.status());
        }
        return get(orderId);
    }

    @Transactional
    public DeliveryOrder approveReschedule(UUID orderId, long expectedVersion, Instant prepEta,
                                           Instant deadline, String reason, String actorId) {
        validateReason(reason, actorId);
        if (prepEta == null || deadline == null || !prepEta.isBefore(deadline)) {
            throw new IllegalArgumentException("prepEta must be before deadline");
        }
        DeliveryOrder current = get(orderId);
        if (current.status() == OrderStatus.CANCELLED || current.status() == OrderStatus.DELIVERED
                || current.status() == OrderStatus.PICKED_UP) {
            throw new com.peakdispatch.domain.order.InvalidOrderTransitionException(current.status(),
                    OrderStatus.WAITING_DISPATCH);
        }
        if (current.version() != expectedVersion) {
            if (sameSchedule(current, prepEta, deadline) && rescheduleAllowed(current.status())) {
                return current;
            }
            throw new OrderVersionConflictException(orderId, current.version(), current.status());
        }
        if (!rescheduleAllowed(current.status())) {
            throw new com.peakdispatch.domain.order.InvalidOrderTransitionException(current.status(),
                    OrderStatus.WAITING_DISPATCH);
        }
        Instant now = Instant.now(clock);
        if (!orders.approveReschedule(orderId, expectedVersion, prepEta, deadline, reason, actorId, now)) {
            DeliveryOrder latest = get(orderId);
            if (sameSchedule(latest, prepEta, deadline) && rescheduleAllowed(latest.status())) {
                return latest;
            }
            throw new OrderVersionConflictException(orderId, latest.version(), latest.status());
        }
        return get(orderId);
    }

    @Transactional
    public DeliveryOrder reassign(UUID orderId, long expectedVersion, String reason, String actorId) {
        validateReason(reason, actorId);
        DeliveryOrder current = get(orderId);
        if (current.version() != expectedVersion) {
            throw new OrderVersionConflictException(orderId, current.version(), current.status());
        }
        if (!reassignmentAllowed(current.status())) {
            throw new com.peakdispatch.domain.order.InvalidOrderTransitionException(current.status(),
                    OrderStatus.WAITING_DISPATCH);
        }
        Instant now = Instant.now(clock);
        current.transitionTo(OrderStatus.WAITING_DISPATCH, now);
        if (!orders.reassign(orderId, expectedVersion, reason, actorId, now)) {
            DeliveryOrder latest = get(orderId);
            throw new OrderVersionConflictException(orderId, latest.version(), latest.status());
        }
        return get(orderId);
    }

    private static boolean rescheduleAllowed(OrderStatus status) {
        return status == OrderStatus.WAITING_DISPATCH || status == OrderStatus.OFFERED
                || status == OrderStatus.ACCEPTED || status == OrderStatus.OPS_ESCALATED;
    }

    private static boolean reassignmentAllowed(OrderStatus status) {
        return status == OrderStatus.OFFERED || status == OrderStatus.ACCEPTED
                || status == OrderStatus.OPS_ESCALATED;
    }

    private static boolean sameSchedule(DeliveryOrder order, Instant prepEta, Instant deadline) {
        return Objects.equals(order.prepEta(), prepEta) && Objects.equals(order.deadline(), deadline);
    }

    private static void validateReason(String reason, String actorId) {
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("reason must be between 1 and 256 characters");
        }
        if (actorId == null || actorId.isBlank() || actorId.length() > 128) {
            throw new IllegalArgumentException("actorId must be between 1 and 128 characters");
        }
    }

    @Transactional(readOnly = true)
    public DeliveryOrder get(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public record CreateOrderCommand(UUID merchantId, String regionId, double pickupLat, double pickupLon,
                                     double dropoffLat, double dropoffLon, Instant prepEta, Instant deadline) {
        public CreateOrderCommand {
            if (merchantId == null || regionId == null || regionId.isBlank()) {
                throw new IllegalArgumentException("merchantId and regionId are required");
            }
            if (Math.abs(pickupLat) > 90 || Math.abs(dropoffLat) > 90
                    || Math.abs(pickupLon) > 180 || Math.abs(dropoffLon) > 180) {
                throw new IllegalArgumentException("coordinates are out of range");
            }
        }
    }
}
