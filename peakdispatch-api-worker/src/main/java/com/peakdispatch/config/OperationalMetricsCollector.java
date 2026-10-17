package com.peakdispatch.config;

import com.peakdispatch.application.observability.PeakDispatchMetrics;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class OperationalMetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(OperationalMetricsCollector.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final PeakDispatchMetrics metrics;
    private final String environment;

    public OperationalMetricsCollector(JdbcTemplate jdbc, StringRedisTemplate redis, PeakDispatchMetrics metrics,
                                        @Value("${peakdispatch.redis.environment:demo}") String environment) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.metrics = metrics;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${peakdispatch.metrics.collect-delay-ms:15000}", initialDelay = 5000)
    public void collect() {
        collectDatabaseMetrics();
        collectRedisMetrics();
    }

    private void collectDatabaseMetrics() {
        try {
            Map<String, Object> outbox = jdbc.queryForMap("""
                    SELECT COUNT(*) AS pending,
                        COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(3)), 0) AS oldest_age
                    FROM outbox_event WHERE published_at IS NULL AND failed_at IS NULL
                    """);
            metrics.outboxState(number(outbox.get("pending")), number(outbox.get("oldest_age")));
            metrics.approvalWaiting(scalar("SELECT COUNT(*) FROM agent_action WHERE status = 'PENDING_CONFIRMATION'"));
            metrics.heartbeatExpiredCurrent(scalar("""
                    SELECT COUNT(*) FROM courier WHERE online_state = 'ONLINE'
                      AND last_position_at < TIMESTAMPADD(SECOND, -90, CURRENT_TIMESTAMP(3))
                    """));
            Long drift = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(ABS(cc.committed_count - (
                        SELECT COUNT(*) FROM dispatch_assignment da WHERE da.courier_id = cc.courier_id
                          AND da.status IN ('ACCEPTED', 'PICKED_UP') AND da.released_at IS NULL
                    )) + ABS(cc.reserved_count - (
                        SELECT COUNT(*) FROM dispatch_offer o WHERE o.courier_id = cc.courier_id
                          AND o.status = 'OFFERED' AND o.expires_at > CURRENT_TIMESTAMP(3)
                    ))), 0) FROM courier_capacity cc
                    """, Long.class);
            metrics.capacityDrift(drift == null ? 0 : drift);
        } catch (RuntimeException failure) {
            log.warn("Operational database metrics collection failed ({})", failure.getClass().getSimpleName());
        }
    }

    private void collectRedisMetrics() {
        long started = System.nanoTime();
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            if (!"PONG".equalsIgnoreCase(pong)) throw new IllegalStateException("Redis ping failed");
            long pending = streamPending();
            long backlog = streamBacklog();
            long retries = 0;
            long dlq = 0;
            try {
                retries = scalar("SELECT COALESCE(SUM(attempts), 0) FROM inbox_message WHERE attempts > 1");
                dlq = scalar("SELECT COUNT(*) FROM inbox_message WHERE status = 'DEAD_LETTER'");
            } catch (RuntimeException ignored) {
                // Stream depth remains useful when the durable Inbox database is unavailable.
            }
            metrics.streamState(pending, backlog,
                    retries, dlq);
            metrics.redisCall(Duration.ofNanos(System.nanoTime() - started), true);
        } catch (RuntimeException failure) {
            metrics.redisCall(Duration.ofNanos(System.nanoTime() - started), false);
            log.warn("Operational Redis metrics collection failed ({})", failure.getClass().getSimpleName());
        }
    }

    private long streamPending() {
        return pending("dispatch", "peakdispatch-dispatch-v1") + pending("events", "peakdispatch-events-v1");
    }

    private long pending(String stream, String group) {
        try {
            PendingMessagesSummary pending = redis.opsForStream().pending(streamKey(stream), group);
            return pending == null ? 0 : pending.getTotalPendingMessages();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private long streamBacklog() {
        return outstanding("dispatch", "peakdispatch-dispatch-v1")
                + outstanding("events", "peakdispatch-events-v1");
    }

    private long outstanding(String stream, String group) {
        try {
            Object response = redis.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XINFO", bytes("GROUPS"), bytes(streamKey(stream))));
            if (response instanceof java.util.List<?> groups) {
                for (Object value : groups) {
                    if (!(value instanceof java.util.List<?> fields)) continue;
                    String name = null;
                    long pending = 0;
                    long lag = 0;
                    for (int index = 0; index + 1 < fields.size(); index += 2) {
                        String field = text(fields.get(index));
                        String fieldValue = text(fields.get(index + 1));
                        if ("name".equals(field)) name = fieldValue;
                        else if ("pending".equals(field)) pending = parseNonNegative(fieldValue);
                        else if ("lag".equals(field)) lag = parseNonNegative(fieldValue);
                    }
                    if (group.equals(name)) return pending + lag;
                }
            }
            return pending(stream, group);
        } catch (RuntimeException ignored) {
            return pending(stream, group);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : value == null ? "" : value.toString();
    }

    private static long parseNonNegative(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String streamKey(String name) { return "pd:" + environment + ":stream:" + name; }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
}
