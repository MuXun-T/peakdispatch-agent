package com.peakdispatch.adapter.mysql;

import com.peakdispatch.application.courier.CourierNotFoundException;
import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.courier.CourierSessionConflictException;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.courier.CourierPresence;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCourierRepository implements CourierRepository {
    private final JdbcTemplate jdbc;
    private final DispatchRepository dispatch;

    public JdbcCourierRepository(JdbcTemplate jdbc, DispatchRepository dispatch) {
        this.jdbc = jdbc;
        this.dispatch = dispatch;
    }

    @Override
    public Optional<CourierPresence> find(UUID courierId) {
        return jdbc.query(PRESENCE_SELECT + " WHERE c.id = ?", (rs, row) -> map(rs), uuidBytes(courierId))
                .stream().findFirst();
    }

    @Override
    public boolean ownedBy(UUID courierId, String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM courier WHERE id = ? AND username = ?",
                Integer.class, uuidBytes(courierId), username);
        return count != null && count == 1;
    }

    @Override
    public List<CourierPresence> findOnlineInRegion(String regionId) {
        return jdbc.query(PRESENCE_SELECT + " WHERE c.region_id = ? AND c.online_state = 'ONLINE'",
                (rs, row) -> map(rs), regionId);
    }

    @Override
    public List<CourierPresence> findOnlineAfter(UUID lastCourierId, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("courier projection page size must be between 1 and 1000");
        }
        String predicate = lastCourierId == null ? " WHERE c.online_state = 'ONLINE'"
                : " WHERE c.online_state = 'ONLINE' AND c.id > ?";
        if (lastCourierId == null) {
            return jdbc.query(PRESENCE_SELECT + predicate + " ORDER BY c.id LIMIT ?",
                    (rs, row) -> map(rs), limit);
        }
        return jdbc.query(PRESENCE_SELECT + predicate + " ORDER BY c.id LIMIT ?",
                (rs, row) -> map(rs), uuidBytes(lastCourierId), limit);
    }

    @Override
    public CourierPresence setOnline(UUID courierId, String regionId, Instant now) {
        lockActiveOfferOrders(courierId);
        CourierPresence current = lock(courierId);
        jdbc.update("""
                UPDATE courier SET region_id = ?, online_state = 'ONLINE',
                    presence_generation = presence_generation + 1, heartbeat_sequence = 0,
                    latitude = NULL, longitude = NULL, last_position_at = NULL
                WHERE id = ?
                """, regionId, uuidBytes(courierId));
        UUID eventId = UUID.randomUUID();
        appendPresenceEvent(eventId, courierId, current.presenceGeneration() + 1, "ONLINE", regionId,
                current.regionId(), now);
        return find(courierId).orElseThrow();
    }

    @Override
    public CourierPresence setOffline(UUID courierId, Instant now) {
        lockActiveOfferOrders(courierId);
        CourierPresence current = lock(courierId);
        jdbc.update("""
                UPDATE courier SET region_id = NULL, online_state = 'OFFLINE',
                    presence_generation = presence_generation + 1, heartbeat_sequence = 0,
                    latitude = NULL, longitude = NULL, last_position_at = NULL
                WHERE id = ?
                """, uuidBytes(courierId));
        UUID eventId = UUID.randomUUID();
        appendPresenceEvent(eventId, courierId, current.presenceGeneration() + 1, "OFFLINE", null,
                current.regionId(), now);
        return find(courierId).orElseThrow();
    }

    @Override
    public CourierPresence heartbeat(UUID courierId, String regionId, long generation, long sequence,
                                     double latitude, double longitude, Instant now) {
        CourierPresence current = lock(courierId);
        if (current.onlineState() != CourierOnlineState.ONLINE
                || current.presenceGeneration() != generation
                || !regionId.equals(current.regionId())) {
            throw new CourierSessionConflictException(courierId, "courier session is stale or belongs to another region");
        }
        if (sequence <= current.sequence()) {
            throw new CourierSessionConflictException(courierId, "heartbeat sequence must increase");
        }
        int changed = jdbc.update("""
                UPDATE courier SET heartbeat_sequence = ?, latitude = ?, longitude = ?, last_position_at = ?
                WHERE id = ? AND online_state = 'ONLINE' AND presence_generation = ? AND heartbeat_sequence < ?
                """, sequence, latitude, longitude, Timestamp.from(now), uuidBytes(courierId), generation, sequence);
        if (changed != 1) {
            throw new CourierSessionConflictException(courierId, "courier session changed during heartbeat");
        }
        return find(courierId).orElseThrow();
    }

    @Override
    public void invalidateStaleOffers(UUID courierId, long currentGeneration, Instant now, String reason) {
        dispatch.invalidateStaleCourierOffers(courierId, currentGeneration, now, reason);
    }

    private void lockActiveOfferOrders(UUID courierId) {
        List<UUID> orderIds = jdbc.query("""
                SELECT order_id FROM dispatch_offer WHERE courier_id = ? AND status = 'OFFERED' ORDER BY order_id
                """, (rs, row) -> uuid(rs.getBytes(1)), uuidBytes(courierId));
        for (UUID orderId : orderIds) {
            jdbc.query("SELECT id FROM delivery_order WHERE id = ? FOR UPDATE", (rs, row) -> rs.getBytes(1),
                    uuidBytes(orderId));
        }
    }

    private CourierPresence lock(UUID courierId) {
        List<CourierPresence> rows = jdbc.query(PRESENCE_SELECT + " WHERE c.id = ? FOR UPDATE",
                (rs, row) -> map(rs), uuidBytes(courierId));
        if (rows.isEmpty()) {
            throw new CourierNotFoundException(courierId);
        }
        return rows.get(0);
    }

    private void appendPresenceEvent(UUID eventId, UUID courierId, long generation,
                                     String state, String regionId, String previousRegionId, Instant now) {
        String payload = "{\"courierId\":\"" + courierId + "\",\"presenceGeneration\":" + generation
                + ",\"onlineState\":\"" + state + "\",\"regionId\":"
                + (regionId == null ? "null" : "\"" + escape(regionId) + "\"")
                + ",\"previousRegionId\":"
                + (previousRegionId == null ? "null" : "\"" + escape(previousRegionId) + "\"") + "}";
        jdbc.update("""
                INSERT INTO delivery_event
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                     actor_type, occurred_at, payload_json)
                VALUES (?, 'COURIER', ?, ?, 'courier.presence_changed', 'COURIER', ?, ?)
                """, uuidBytes(eventId), uuidBytes(courierId), generation, Timestamp.from(now), payload);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                     dedupe_key, payload_json, created_at, next_attempt_at)
                VALUES (?, 'courier.presence_changed', 'COURIER', ?, ?, ?, ?, ?, ?)
                """, uuidBytes(eventId), uuidBytes(courierId), generation,
                "courier:" + courierId + ":presence:" + generation, payload,
                Timestamp.from(now), Timestamp.from(now));
    }

    private static CourierPresence map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp heartbeat = rs.getTimestamp("last_position_at");
        java.math.BigDecimal lat = rs.getBigDecimal("latitude");
        java.math.BigDecimal lon = rs.getBigDecimal("longitude");
        return new CourierPresence(uuid(rs.getBytes("id")), rs.getString("region_id"),
                CourierOnlineState.valueOf(rs.getString("online_state")), rs.getLong("presence_generation"),
                rs.getLong("heartbeat_sequence"), lat == null ? null : lat.doubleValue(),
                lon == null ? null : lon.doubleValue(), heartbeat == null ? null : heartbeat.toInstant(),
                rs.getInt("capacity_limit"), rs.getInt("committed_count"), rs.getInt("reserved_count"),
                rs.getLong("capacity_version"), rs.getTimestamp("capacity_updated_at").toInstant());
    }

    private static final String PRESENCE_SELECT = """
            SELECT c.id, c.region_id, c.online_state, c.presence_generation, c.heartbeat_sequence,
                   c.latitude, c.longitude, c.last_position_at, cap.capacity_limit,
                   cap.committed_count, cap.reserved_count, cap.version AS capacity_version,
                   cap.updated_at AS capacity_updated_at
            FROM courier c JOIN courier_capacity cap ON cap.courier_id = c.id
            """;

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

}
