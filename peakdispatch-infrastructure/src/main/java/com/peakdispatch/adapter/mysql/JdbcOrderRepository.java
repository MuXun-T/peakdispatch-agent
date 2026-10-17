package com.peakdispatch.adapter.mysql;

import com.peakdispatch.application.order.OrderRepository;
import com.peakdispatch.application.order.PreparationEventConflictException;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import com.peakdispatch.domain.order.OrderTransitionPolicy;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.time.Instant;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderRepository implements OrderRepository {
    private final JdbcTemplate jdbc;
    private final DispatchRepository dispatch;

    public JdbcOrderRepository(JdbcTemplate jdbc, DispatchRepository dispatch) {
        this.jdbc = jdbc;
        this.dispatch = dispatch;
    }

    @Override
    public DeliveryOrder insert(DeliveryOrder order) {
        jdbc.update("""
                INSERT INTO delivery_order
                    (id, merchant_id, region_id, pickup_lat, pickup_lon, dropoff_lat, dropoff_lon,
                     prep_eta, deadline, status, version, dispatch_round, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, uuidBytes(order.id()), uuidBytes(order.merchantId()), order.regionId(),
                order.pickupLat(), order.pickupLon(), order.dropoffLat(), order.dropoffLon(),
                Timestamp.from(order.prepEta()), Timestamp.from(order.deadline()), order.status().name(),
                order.version(), Timestamp.from(order.createdAt()), Timestamp.from(order.updatedAt()));
        jdbc.update("""
                INSERT INTO order_dispatch_slot (order_id, phase, slot_version, updated_at)
                VALUES (?, 'IDLE', 0, ?)
                """, uuidBytes(order.id()), Timestamp.from(order.updatedAt()));
        String payload = "{\"orderId\":\"" + order.id() + "\",\"merchantId\":\""
                + order.merchantId() + "\",\"regionId\":\"" + jsonEscape(order.regionId())
                + "\",\"deadline\":\"" + order.deadline() + "\"}";
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO delivery_event
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                     actor_type, actor_id, occurred_at, payload_json)
                VALUES (?, 'ORDER', ?, ?, 'order.created', 'MERCHANT', ?, ?, ?)
                """, uuidBytes(eventId), uuidBytes(order.id()), order.version(),
                order.merchantId().toString(), Timestamp.from(order.createdAt()), payload);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                     dedupe_key, payload_json, created_at, next_attempt_at)
                VALUES (?, 'order.created', 'ORDER', ?, ?, ?, ?, ?, ?)
                """, uuidBytes(eventId), uuidBytes(order.id()), order.version(),
                "order:" + order.id() + ":created:v" + order.version(), payload,
                Timestamp.from(order.createdAt()), Timestamp.from(order.createdAt()));
        return order;
    }

    @Override
    public Optional<DeliveryOrder> findById(UUID id) {
        return jdbc.query("""
                        SELECT id, merchant_id, region_id, pickup_lat, pickup_lon, dropoff_lat, dropoff_lon,
                               prep_eta, deadline, preparation_ready, status, version, created_at, updated_at
                        FROM delivery_order WHERE id = ?
                        """,
                (rs, rowNum) -> new DeliveryOrder(
                        uuid(rs.getBytes("id")), uuid(rs.getBytes("merchant_id")), rs.getString("region_id"),
                        rs.getBigDecimal("pickup_lat").doubleValue(), rs.getBigDecimal("pickup_lon").doubleValue(),
                        rs.getBigDecimal("dropoff_lat").doubleValue(), rs.getBigDecimal("dropoff_lon").doubleValue(),
                        rs.getTimestamp("prep_eta").toInstant(), rs.getTimestamp("deadline").toInstant(),
                        rs.getBoolean("preparation_ready"), OrderStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                uuidBytes(id)).stream().findFirst();
    }

    @Override
    public boolean merchantCanAccept(UUID merchantId, String regionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM merchant WHERE id = ? AND active = TRUE AND region_id = ?
                """, Integer.class, uuidBytes(merchantId), regionId);
        return count != null && count > 0;
    }

    @Override
    public boolean merchantOwnedBy(UUID merchantId, String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM merchant WHERE id = ? AND username = ?",
                Integer.class, uuidBytes(merchantId), username);
        return count != null && count == 1;
    }

    @Override
    public boolean merchantOwnsOrder(UUID orderId, String username) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM delivery_order o JOIN merchant m ON m.id = o.merchant_id
                WHERE o.id = ? AND m.username = ?
                """, Integer.class, uuidBytes(orderId), username);
        return count != null && count == 1;
    }

    @Override
    public boolean courierWasAssigned(UUID orderId, String username) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_assignment a JOIN courier c ON c.id = a.courier_id
                WHERE a.order_id = ? AND c.username = ?
                """, Integer.class, uuidBytes(orderId), username);
        return count != null && count > 0;
    }

    @Override
    public boolean orderInRegion(UUID orderId, String regionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE id = ? AND region_id = ?",
                Integer.class, uuidBytes(orderId), regionId);
        return count != null && count == 1;
    }

    @Override
    public boolean orderInRegions(UUID orderId, Set<String> regionIds) {
        if (regionIds == null || regionIds.isEmpty()) {
            return false;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(regionIds.size(), "?"));
        Object[] parameters = new Object[regionIds.size() + 1];
        parameters[0] = uuidBytes(orderId);
        int index = 1;
        for (String regionId : regionIds) {
            parameters[index++] = regionId;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE id = ? AND region_id IN ("
                + placeholders + ")", Integer.class, parameters);
        return count != null && count == 1;
    }

    @Override
    public Optional<OrderRepository.PreparationEvent> findPreparationEvent(UUID eventId) {
        return jdbc.query("""
                SELECT aggregate_id, actor_id, payload_json FROM delivery_event
                WHERE event_id = ? AND event_type = 'merchant.prep_updated'
                """, (rs, row) -> {
                    String payload = rs.getString("payload_json");
                    java.util.regex.Matcher eta = java.util.regex.Pattern.compile("\\\"prepEta\\\":\\\"([^\\\"]+)\\\"").matcher(payload);
                    java.util.regex.Matcher ready = java.util.regex.Pattern.compile("\\\"ready\\\":(true|false)").matcher(payload);
                    Instant prepEta = eta.find() ? Instant.parse(eta.group(1)) : null;
                    boolean isReady = ready.find() && Boolean.parseBoolean(ready.group(1));
                    return new OrderRepository.PreparationEvent(uuid(rs.getBytes("aggregate_id")),
                            rs.getString("actor_id"), prepEta, isReady);
                }, uuidBytes(eventId)).stream().findFirst();
    }

    @Override
    public boolean transitionStatus(UUID orderId, long expectedVersion, OrderStatus expectedStatus,
                                    OrderStatus targetStatus, Instant updatedAt) {
        int changed = jdbc.update("""
                UPDATE delivery_order
                SET status = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND status = ?
                """, targetStatus.name(), Timestamp.from(updatedAt), uuidBytes(orderId),
                expectedVersion, expectedStatus.name());
        if (changed == 0) {
            return false;
        }
        DeliveryOrder after = findById(orderId).orElseThrow();
        UUID eventId = UUID.randomUUID();
        String payload = "{\"orderId\":\"" + orderId + "\",\"from\":\""
                + expectedStatus.name() + "\",\"to\":\"" + targetStatus.name()
                + "\",\"version\":" + after.version() + "}";
        jdbc.update("""
                INSERT INTO delivery_event
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                     actor_type, occurred_at, payload_json)
                VALUES (?, 'ORDER', ?, ?, ?, 'SYSTEM', ?, ?)
                """, uuidBytes(eventId), uuidBytes(orderId), after.version(),
                "order.status_changed", Timestamp.from(updatedAt), payload);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                     dedupe_key, payload_json, created_at, next_attempt_at)
                VALUES (?, 'order.status_changed', 'ORDER', ?, ?, ?, ?, ?, ?)
                """, uuidBytes(eventId), uuidBytes(orderId), after.version(),
                "order:" + orderId + ":status:v" + after.version(), payload,
                Timestamp.from(updatedAt), Timestamp.from(updatedAt));
        return true;
    }

    @Override
    public boolean updatePreparation(UUID orderId, long expectedVersion, Instant prepEta, boolean ready,
                                     UUID eventId, String actorId, Instant updatedAt) {
        Integer seen = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_event WHERE event_id = ?",
                Integer.class, uuidBytes(eventId));
        if (seen != null && seen > 0) {
            throw new PreparationEventConflictException(eventId);
        }
        DeliveryOrder current = findById(orderId).orElseThrow();
        int changed;
        if (current.status() == OrderStatus.WAITING_DISPATCH) {
            changed = jdbc.update("""
                    UPDATE delivery_order
                    SET prep_eta = ?, preparation_ready = ?, version = version + 1,
                        next_dispatch_at = ?, updated_at = ?
                    WHERE id = ? AND version = ?
                    """, Timestamp.from(prepEta), ready, Timestamp.from(updatedAt), Timestamp.from(updatedAt),
                    uuidBytes(orderId), expectedVersion);
        } else {
            changed = jdbc.update("""
                    UPDATE delivery_order SET prep_eta = ?, preparation_ready = ?, version = version + 1, updated_at = ?
                    WHERE id = ? AND version = ?
                    """, Timestamp.from(prepEta), ready, Timestamp.from(updatedAt), uuidBytes(orderId), expectedVersion);
        }
        if (changed == 0) {
            return false;
        }
        DeliveryOrder after = findById(orderId).orElseThrow();
        jdbc.update("UPDATE merchant SET prep_state = ?, updated_at = ? WHERE id = ?",
                ready ? "READY" : "PREPARING", Timestamp.from(updatedAt), uuidBytes(current.merchantId()));
        String payload = "{\"orderId\":\"" + orderId + "\",\"prepEta\":\"" + prepEta
                + "\",\"ready\":" + ready + ",\"sourceEventId\":\"" + eventId + "\"}";
        jdbc.update("""
                INSERT INTO delivery_event
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                     actor_type, actor_id, occurred_at, payload_json)
                VALUES (?, 'ORDER', ?, ?, 'merchant.prep_updated', 'MERCHANT', ?, ?, ?)
                """, uuidBytes(eventId), uuidBytes(orderId), after.version(), actorId,
                Timestamp.from(updatedAt), payload);
        UUID outboxId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                     dedupe_key, payload_json, created_at, next_attempt_at)
                VALUES (?, 'merchant.prep_updated', 'ORDER', ?, ?, ?, ?, ?, ?)
                """, uuidBytes(outboxId), uuidBytes(orderId), after.version(),
                "order:" + orderId + ":prep:v" + after.version(), payload,
                Timestamp.from(updatedAt), Timestamp.from(updatedAt));
        dispatch.reassessPreparationOffer(orderId, prepEta, ready, updatedAt);
        return true;
    }

    @Override
    public boolean cancel(UUID orderId, long expectedVersion, String reason, String actorId, Instant updatedAt) {
        DeliveryOrder current = findByIdForUpdate(orderId).orElse(null);
        if (current == null) return false;
        if (current.status() == OrderStatus.CANCELLED) return true;
        if (current.version() != expectedVersion
                || !OrderTransitionPolicy.canTransition(current.status(), OrderStatus.CANCELLED)) {
            return false;
        }
        dispatch.releaseOrderResources(orderId, "ORDER_CANCELLED", updatedAt);
        int changed = jdbc.update("""
                UPDATE delivery_order SET status='CANCELLED', version=version+1, next_dispatch_at=NULL, updated_at=?
                WHERE id=? AND version=? AND status=?
                """, Timestamp.from(updatedAt), uuidBytes(orderId), expectedVersion, current.status().name());
        if (changed != 1) return false;
        jdbc.update("""
                UPDATE order_dispatch_slot SET phase='CLOSED', offer_id=NULL, courier_id=NULL,
                    slot_version=slot_version+1, updated_at=? WHERE order_id=?
                """,
                Timestamp.from(updatedAt), uuidBytes(orderId));
        DeliveryOrder after = findById(orderId).orElseThrow();
        String payload = "{\"orderId\":\"" + orderId + "\",\"reason\":\"" + jsonEscape(reason) +
                "\",\"actorId\":\"" + jsonEscape(actorId) + "\"}";
        appendOrderEvent(UUID.randomUUID(), orderId, after.version(), "order.cancelled", payload, updatedAt, actorId);
        appendOrderOutbox(orderId, after.version(), "order.cancelled", "order:" + orderId + ":cancelled:v" + after.version(), payload, updatedAt, updatedAt);
        audit(actorId, "ORDER_CANCELLED", orderId, expectedVersion, after.version(), reason, updatedAt);
        return true;
    }

    @Override
    public boolean approveReschedule(UUID orderId, long expectedVersion, Instant prepEta, Instant deadline,
                                     String reason, String actorId, Instant updatedAt) {
        DeliveryOrder current = findByIdForUpdate(orderId).orElse(null);
        if (current == null || current.version() != expectedVersion || !rescheduleAllowed(current.status())) return false;
        dispatch.releaseOrderResources(orderId, "RESCHEDULED", updatedAt);
        int changed = jdbc.update("""
                UPDATE delivery_order SET prep_eta=?, deadline=?, status='WAITING_DISPATCH', version=version+1,
                       dispatch_round=1, next_dispatch_at=?, updated_at=?
                WHERE id=? AND version=?
                """, Timestamp.from(prepEta), Timestamp.from(deadline), Timestamp.from(updatedAt),
                Timestamp.from(updatedAt), uuidBytes(orderId), expectedVersion);
        if (changed != 1) return false;
        jdbc.update("""
                UPDATE order_dispatch_slot SET phase='IDLE', offer_id=NULL, courier_id=NULL,
                    slot_version=slot_version+1, updated_at=? WHERE order_id=?
                """,
                Timestamp.from(updatedAt), uuidBytes(orderId));
        DeliveryOrder after = findById(orderId).orElseThrow();
        String payload = "{\"orderId\":\"" + orderId + "\",\"prepEta\":\"" + prepEta +
                "\",\"deadline\":\"" + deadline + "\",\"reason\":\"" + jsonEscape(reason) +
                "\",\"actorId\":\"" + jsonEscape(actorId) + "\"}";
        appendOrderEvent(UUID.randomUUID(), orderId, after.version(), "order.rescheduled", payload, updatedAt, actorId);
        appendOrderOutbox(orderId, after.version(), "order.dispatch_requested", "dispatch:" + orderId + ":rescheduled:" + after.version(), payload, updatedAt, updatedAt);
        audit(actorId, "ORDER_RESCHEDULED", orderId, expectedVersion, after.version(), reason, updatedAt);
        return true;
    }

    @Override
    public boolean reassign(UUID orderId, long expectedVersion, String reason, String actorId, Instant updatedAt) {
        DeliveryOrder current = findByIdForUpdate(orderId).orElse(null);
        if (current == null || current.version() != expectedVersion || !reassignmentAllowed(current.status())) {
            return false;
        }
        DeliveryOrder after = current.transitionTo(OrderStatus.WAITING_DISPATCH, updatedAt);
        dispatch.releaseOrderResources(orderId, "REASSIGNED", updatedAt);
        int changed = jdbc.update("""
                UPDATE delivery_order SET status='WAITING_DISPATCH', version=?, dispatch_round=1,
                       next_dispatch_at=?, updated_at=?
                WHERE id=? AND version=? AND status=?
                """, after.version(), Timestamp.from(updatedAt), Timestamp.from(updatedAt),
                uuidBytes(orderId), expectedVersion, current.status().name());
        if (changed != 1) return false;
        jdbc.update("""
                UPDATE order_dispatch_slot SET phase='IDLE', offer_id=NULL, courier_id=NULL,
                    slot_version=slot_version+1, updated_at=? WHERE order_id=?
                """, Timestamp.from(updatedAt), uuidBytes(orderId));
        DeliveryOrder persisted = findById(orderId).orElseThrow();
        String payload = "{\"orderId\":\"" + orderId + "\",\"reason\":\"" + jsonEscape(reason)
                + "\",\"actorId\":\"" + jsonEscape(actorId) + "\",\"previousStatus\":\""
                + current.status().name() + "\",\"newStatus\":\"WAITING_DISPATCH\"}";
        appendOrderEvent(UUID.randomUUID(), orderId, persisted.version(), "order.reassignment_requested",
                payload, updatedAt, actorId);
        appendOrderOutbox(orderId, persisted.version(), "order.dispatch_requested",
                "dispatch:" + orderId + ":reassigned:" + persisted.version(), payload, updatedAt, updatedAt);
        audit(actorId, "ORDER_REASSIGNED", orderId, expectedVersion, persisted.version(), reason, updatedAt);
        return true;
    }

    private static boolean rescheduleAllowed(OrderStatus status) {
        return status == OrderStatus.WAITING_DISPATCH || status == OrderStatus.OFFERED
                || status == OrderStatus.ACCEPTED || status == OrderStatus.OPS_ESCALATED;
    }

    private static boolean reassignmentAllowed(OrderStatus status) {
        return status == OrderStatus.OFFERED || status == OrderStatus.ACCEPTED
                || status == OrderStatus.OPS_ESCALATED;
    }

    private Optional<DeliveryOrder> findByIdForUpdate(UUID id) {
        return jdbc.query("""
                SELECT id, merchant_id, region_id, pickup_lat, pickup_lon, dropoff_lat, dropoff_lon,
                       prep_eta, deadline, preparation_ready, status, version, created_at, updated_at
                FROM delivery_order WHERE id=? FOR UPDATE
                """, (rs, row) -> new DeliveryOrder(uuid(rs.getBytes("id")), uuid(rs.getBytes("merchant_id")),
                rs.getString("region_id"), rs.getBigDecimal("pickup_lat").doubleValue(), rs.getBigDecimal("pickup_lon").doubleValue(),
                rs.getBigDecimal("dropoff_lat").doubleValue(), rs.getBigDecimal("dropoff_lon").doubleValue(),
                rs.getTimestamp("prep_eta").toInstant(), rs.getTimestamp("deadline").toInstant(),
                rs.getBoolean("preparation_ready"), OrderStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), uuidBytes(id)).stream().findFirst();
    }

    private void audit(String actorId, String action, UUID targetId, long before, long after, String details, Instant now) {
        jdbc.update("""
                INSERT INTO audit_log(id,actor_id,action,target_type,target_id,decision,before_version,after_version,details_redacted,created_at)
                VALUES(?,?,?,?,?,'APPLIED',?,?,?,?)
                """, uuidBytes(UUID.randomUUID()), actorId, action, "ORDER", targetId.toString(), before, after,
                SensitiveDataRedactor.redact(details), Timestamp.from(now));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private void appendOrderEvent(UUID eventId, UUID orderId, long version, String eventType,
                                  String payload, Instant now, String actorId) {
        jdbc.update("""
                INSERT INTO delivery_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,actor_type,actor_id,occurred_at,payload_json)
                VALUES(?,'ORDER',?,?,?,'USER',?,?,?)
                """, uuidBytes(eventId), uuidBytes(orderId), version, eventType, actorId, Timestamp.from(now), payload);
    }

    private void appendOrderOutbox(UUID orderId, long version, String topic, String dedupeKey, String payload,
                                   Instant createdAt, Instant nextAttemptAt) {
        jdbc.update("""
                INSERT INTO outbox_event(event_id,topic,aggregate_type,aggregate_id,aggregate_version,
                    dedupe_key,payload_json,created_at,next_attempt_at)
                VALUES(? ,?,'ORDER',?,?,?,?,?,?)
                """, uuidBytes(UUID.randomUUID()), topic, uuidBytes(orderId), version, dedupeKey, payload,
                Timestamp.from(createdAt), Timestamp.from(nextAttemptAt));
    }
}
