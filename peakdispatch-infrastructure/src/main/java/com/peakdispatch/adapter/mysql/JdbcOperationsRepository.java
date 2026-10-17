package com.peakdispatch.adapter.mysql;

import com.peakdispatch.application.operations.OperationsRepository;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.order.OrderStatus;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOperationsRepository implements OperationsRepository {
    private final JdbcTemplate jdbc;

    public JdbcOperationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DashboardSnapshot dashboard(String regionId, Instant from, Instant to, Instant asOf) {
        long[] orders = jdbc.queryForObject("""
                SELECT COUNT(*),
                    COALESCE(SUM(CASE WHEN status = 'WAITING_DISPATCH' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN status = 'OFFERED' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN status = 'ACCEPTED' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN status = 'PICKED_UP' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN status = 'DELIVERED' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0)
                FROM delivery_order WHERE region_id = ? AND created_at >= ? AND created_at < ?
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7)}, regionId, ts(from), ts(to));
        long[] offers = jdbc.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(CASE WHEN f.status = 'OFFERED' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN f.status = 'ACCEPTED' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN f.status = 'REJECTED' THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN f.status = 'EXPIRED' THEN 1 ELSE 0 END), 0)
                FROM dispatch_offer f JOIN delivery_order o ON o.id = f.order_id
                WHERE o.region_id = ? AND f.created_at >= ? AND f.created_at < ?
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5)}, regionId, ts(from), ts(to));
        long[] attempts = jdbc.queryForObject("""
                SELECT COUNT(*),
                    COALESCE(SUM(CASE WHEN outcome IN ('NO_CANDIDATE', 'REDIS_UNAVAILABLE', 'CONTENDED', 'FAILED') THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN outcome = 'OPS_ESCALATION' THEN 1 ELSE 0 END), 0)
                FROM dispatch_attempt a JOIN delivery_order o ON o.id = a.order_id
                WHERE o.region_id = ? AND a.started_at >= ? AND a.started_at < ?
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)},
                regionId, ts(from), ts(to));
        long[] capacity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(c.capacity_limit), 0), COALESCE(SUM(c.committed_count), 0),
                    COALESCE(SUM(c.reserved_count), 0),
                    COALESCE(SUM(CASE WHEN r.online_state = 'ONLINE' THEN 1 ELSE 0 END), 0)
                FROM courier r JOIN courier_capacity c ON c.courier_id = r.id WHERE r.region_id = ?
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, regionId);
        return new DashboardSnapshot(regionId, from, to, asOf, orders[0], orders[1], orders[2], orders[3],
                orders[4], orders[5], orders[6], offers[1], offers[0], offers[2], offers[3], offers[4],
                attempts[0], attempts[1], attempts[2], capacity[3], capacity[0], capacity[1], capacity[2]);
    }

    @Override
    public String orderRegion(UUID orderId) {
        return jdbc.query("SELECT region_id FROM delivery_order WHERE id = ?",
                (rs, row) -> rs.getString(1), bytes(orderId)).stream().findFirst().orElse(null);
    }

    @Override
    public String courierRegion(UUID courierId) {
        return jdbc.query("SELECT region_id FROM courier WHERE id = ?",
                (rs, row) -> rs.getString(1), bytes(courierId)).stream().findFirst().orElse(null);
    }

    @Override
    public List<TimelineEntry> orderTimeline(UUID orderId) {
        List<TimelineEntry> entries = new ArrayList<>();
        entries.addAll(jdbc.query("""
                SELECT event_id AS evidence_id, occurred_at, event_type, actor_id, aggregate_version,
                    payload_json AS details
                FROM delivery_event WHERE aggregate_type = 'ORDER' AND aggregate_id = ?
                """, (rs, row) -> entry(rs, "EVENT"), bytes(orderId)));
        entries.addAll(jdbc.query("""
                SELECT started_at AS occurred_at, CONCAT('DISPATCH_', outcome) AS event_type,
                    NULL AS actor_id, round_no AS aggregate_version, id, policy_version,
                    policy_snapshot, candidate_count, selected_courier_id, exclusion_summary, finished_at
                FROM dispatch_attempt WHERE order_id = ?
                """, (rs, row) -> new TimelineEntry(rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("event_type"), "DISPATCH_ATTEMPT", null, rs.getLong("aggregate_version"),
                        "attemptId=" + uuid(rs.getBytes("id")) + ";policyVersion=" + rs.getObject("policy_version")
                                + ";policySnapshot=" + rs.getString("policy_snapshot") + ";candidateCount="
                                + rs.getInt("candidate_count") + ";selectedCourierId="
                                + uuidNullable(rs.getBytes("selected_courier_id")) + ";exclusionSummary="
                                + rs.getString("exclusion_summary") + ";finishedAt=" + rs.getString("finished_at"),
                        "attempt:" + uuid(rs.getBytes("id"))),
                bytes(orderId)));
        entries.addAll(jdbc.query("""
                SELECT a.started_at AS occurred_at,
                    CONCAT('CANDIDATE_', CASE WHEN d.eligible THEN 'ELIGIBLE' ELSE 'EXCLUDED' END) AS event_type,
                    d.courier_id, a.round_no AS aggregate_version, a.id AS attempt_id, d.eligible,
                    d.reason_code, d.rank_no, d.score, d.feature_snapshot
                FROM dispatch_candidate_decision d
                JOIN dispatch_attempt a ON a.id = d.attempt_id
                WHERE a.order_id = ?
                """, (rs, row) -> new TimelineEntry(rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("event_type"), "DISPATCH_CANDIDATE",
                        uuid(rs.getBytes("courier_id")).toString(), rs.getLong("aggregate_version"),
                        "attemptId=" + uuid(rs.getBytes("attempt_id")) + ";candidateId="
                                + uuid(rs.getBytes("courier_id")) + ";eligible=" + rs.getBoolean("eligible")
                                + ";reasonCode=" + rs.getString("reason_code") + ";rank="
                                + rs.getObject("rank_no") + ";score=" + rs.getObject("score")
                                + ";featureSnapshot=" + rs.getString("feature_snapshot"),
                        "candidate:" + uuid(rs.getBytes("attempt_id")) + ":"
                                + uuid(rs.getBytes("courier_id"))), bytes(orderId)));
        entries.addAll(jdbc.query("""
                SELECT f.created_at AS occurred_at, CONCAT('OFFER_', f.status) AS event_type,
                    f.courier_id, f.id, f.round_no, f.order_version, f.expires_at, f.policy_id,
                    f.policy_version, f.policy_snapshot, f.score_detail
                FROM dispatch_offer f WHERE f.order_id = ?
                """, (rs, row) -> new TimelineEntry(rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("event_type"), "DISPATCH_OFFER", uuid(rs.getBytes("courier_id"))
                        .toString(), rs.getLong("order_version"), "offerId=" + uuid(rs.getBytes("id"))
                        + ";round=" + rs.getInt("round_no") + ";expiresAt=" + rs.getString("expires_at")
                        + ";policyId=" + uuidNullable(rs.getBytes("policy_id")) + ";policyVersion="
                        + rs.getObject("policy_version") + ";policySnapshot=" + rs.getString("policy_snapshot")
                        + ";scoreDetail=" + rs.getString("score_detail"),
                        "offer:" + uuid(rs.getBytes("id"))), bytes(orderId)));
        entries.addAll(jdbc.query("""
                SELECT id AS evidence_id, created_at AS occurred_at, action AS event_type, actor_id,
                    after_version AS aggregate_version,
                    COALESCE(details_redacted, decision) AS details
                FROM audit_log WHERE target_type = 'ORDER' AND target_id = ?
                """, (rs, row) -> entry(rs, "AUDIT"), orderId.toString()));
        entries.sort(Comparator.comparing(TimelineEntry::occurredAt).thenComparing(TimelineEntry::source)
                .thenComparing(TimelineEntry::type));
        return List.copyOf(entries);
    }

    @Override
    public List<DispatchAttemptEvidence> dispatchEvidence(UUID orderId) {
        List<DispatchAttemptEvidence> attempts = jdbc.query("""
                SELECT id, round_no, order_version, outcome, candidate_count, selected_courier_id,
                    policy_version, exclusion_summary, started_at, finished_at
                FROM dispatch_attempt WHERE order_id = ? ORDER BY round_no, started_at, id
                """, (rs, row) -> {
            UUID attemptId = uuid(rs.getBytes("id"));
            Timestamp finished = rs.getTimestamp("finished_at");
            Long policyVersion = nullableLong(rs.getObject("policy_version"));
            List<CandidateDecisionEvidence> candidates = jdbc.query("""
                    SELECT courier_id, eligible, reason_code, rank_no, score, feature_snapshot
                    FROM dispatch_candidate_decision WHERE attempt_id = ?
                    ORDER BY CASE WHEN rank_no IS NULL THEN 1 ELSE 0 END, rank_no, courier_id
                    """, (candidate, candidateRow) -> new CandidateDecisionEvidence(
                    "candidate:" + attemptId + ":" + uuid(candidate.getBytes("courier_id")),
                    uuid(candidate.getBytes("courier_id")), candidate.getBoolean("eligible"),
                    candidate.getString("reason_code"), nullableInt(candidate.getObject("rank_no")),
                    nullableDouble(candidate.getObject("score")), candidate.getString("feature_snapshot")),
                    bytes(attemptId));
            return new DispatchAttemptEvidence("attempt:" + attemptId, attemptId,
                    rs.getInt("round_no"), rs.getLong("order_version"), rs.getString("outcome"),
                    rs.getInt("candidate_count"), uuidNullableValue(rs.getBytes("selected_courier_id")),
                    policyVersion, rs.getString("exclusion_summary"),
                    rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant(),
                    candidates);
        }, bytes(orderId));
        return List.copyOf(attempts);
    }

    @Override
    public OrderPage searchOrders(String regionId, OrderStatus status, UUID orderId, Instant createdFrom,
                                  Instant createdTo, int page, int size, Instant asOf) {
        StringBuilder where = new StringBuilder(" FROM delivery_order o LEFT JOIN order_dispatch_slot s ON s.order_id=o.id WHERE o.region_id=?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(regionId);
        if (status != null) {
            where.append(" AND o.status=?");
            parameters.add(status.name());
        }
        if (orderId != null) {
            where.append(" AND o.id=?");
            parameters.add(bytes(orderId));
        }
        if (createdFrom != null) {
            where.append(" AND o.created_at>=?");
            parameters.add(ts(createdFrom));
        }
        if (createdTo != null) {
            where.append(" AND o.created_at<?");
            parameters.add(ts(createdTo));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add((long) page * size);
        List<OrderRow> items = jdbc.query("""
                SELECT o.id, o.merchant_id, o.region_id, o.status, o.version, o.prep_eta, o.deadline,
                    o.created_at, o.updated_at, s.phase, s.courier_id
                """ + where + " ORDER BY o.created_at DESC, o.id LIMIT ? OFFSET ?",
                (rs, row) -> new OrderRow(uuid(rs.getBytes("id")), uuid(rs.getBytes("merchant_id")),
                        rs.getString("region_id"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getLong("version"), rs.getTimestamp("prep_eta").toInstant(),
                        rs.getTimestamp("deadline").toInstant(), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getString("phase"),
                        uuidNullableValue(rs.getBytes("courier_id"))), pageParameters.toArray());
        return new OrderPage(List.copyOf(items), page, size, total == null ? 0 : total, asOf, "mysql_primary");
    }

    @Override
    public CourierPage searchCouriers(String regionId, CourierOnlineState onlineState, UUID courierId,
                                     Long presenceGeneration, int page, int size, Instant asOf) {
        StringBuilder where = new StringBuilder(" FROM courier r JOIN courier_capacity c ON c.courier_id=r.id WHERE r.region_id=?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(regionId);
        if (onlineState != null) {
            where.append(" AND r.online_state=?");
            parameters.add(onlineState.name());
        }
        if (courierId != null) {
            where.append(" AND r.id=?");
            parameters.add(bytes(courierId));
        }
        if (presenceGeneration != null) {
            where.append(" AND r.presence_generation=?");
            parameters.add(presenceGeneration);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add((long) page * size);
        List<CourierRow> items = jdbc.query("""
                SELECT r.id, r.region_id, r.online_state, r.presence_generation, r.last_position_at,
                    c.capacity_limit, c.committed_count, c.reserved_count, c.version
                """ + where + " ORDER BY r.id LIMIT ? OFFSET ?",
                (rs, row) -> {
                    int limit = rs.getInt("capacity_limit");
                    int committed = rs.getInt("committed_count");
                    int reserved = rs.getInt("reserved_count");
                    Timestamp heartbeat = rs.getTimestamp("last_position_at");
                    return new CourierRow(uuid(rs.getBytes("id")), rs.getString("region_id"),
                            CourierOnlineState.valueOf(rs.getString("online_state")),
                            rs.getLong("presence_generation"), heartbeat == null ? null : heartbeat.toInstant(),
                            limit, committed, reserved, Math.max(0, limit - committed - reserved),
                            rs.getLong("version"));
                }, pageParameters.toArray());
        return new CourierPage(List.copyOf(items), page, size, total == null ? 0 : total, asOf, "mysql_primary");
    }

    @Override
    public void audit(String actorId, String action, String targetType, String targetId, String decision,
                      String details, String traceId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision,
                    trace_id, details_redacted, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(UUID.randomUUID()), actorId, action, targetType, targetId, decision,
                traceId, SensitiveDataRedactor.redact(details), ts(occurredAt));
    }

    private static TimelineEntry entry(ResultSet rs, String source) throws SQLException {
        long version = rs.getLong("aggregate_version");
        Long actualVersion = rs.wasNull() ? null : version;
        Timestamp timestamp = rs.getTimestamp("occurred_at");
        return new TimelineEntry(timestamp.toInstant(), rs.getString("event_type"), source,
                rs.getString("actor_id"), actualVersion, rs.getString("details"),
                source.toLowerCase() + ":" + uuid(rs.getBytes("evidence_id")));
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer nullableInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID uuidNullableValue(byte[] value) {
        return value == null ? null : uuid(value);
    }

    private static String uuidNullable(byte[] value) {
        return value == null ? null : uuid(value).toString();
    }
}
