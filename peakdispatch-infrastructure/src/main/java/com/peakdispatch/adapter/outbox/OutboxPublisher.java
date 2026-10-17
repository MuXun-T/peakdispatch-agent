package com.peakdispatch.adapter.outbox;

import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once publisher. MySQL remains the durable source until XADD succeeds. */
@Component
@Profile("!test")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'API'")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final TransactionTemplate claimTransactions;
    private final Clock clock;
    private final String environment;
    private final int maxAttempts;
    private final long claimSeconds;
    private final PeakDispatchMetrics metrics;

    public OutboxPublisher(JdbcTemplate jdbc, StringRedisTemplate redis,
                           PlatformTransactionManager transactionManager, Clock clock,
                           @Value("${peakdispatch.redis.environment:demo}") String environment,
                           @Value("${peakdispatch.worker.outbox-max-attempts:10}") int maxAttempts,
                           @Value("${peakdispatch.worker.outbox-claim-seconds:30}") long claimSeconds) {
        this(jdbc, redis, transactionManager, clock, environment, maxAttempts, claimSeconds, null);
    }

    @Autowired
    public OutboxPublisher(JdbcTemplate jdbc, StringRedisTemplate redis,
                           PlatformTransactionManager transactionManager, Clock clock,
                           @Value("${peakdispatch.redis.environment:demo}") String environment,
                           @Value("${peakdispatch.worker.outbox-max-attempts:10}") int maxAttempts,
                           @Value("${peakdispatch.worker.outbox-claim-seconds:30}") long claimSeconds,
                           PeakDispatchMetrics metrics) {
        if (environment == null || !environment.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Redis environment must be a simple identifier");
        }
        if (maxAttempts < 1 || claimSeconds < 1) {
            throw new IllegalArgumentException("Outbox retry and claim settings must be positive");
        }
        this.jdbc = jdbc;
        this.redis = redis;
        this.transactions = new TransactionTemplate(transactionManager);
        this.claimTransactions = new TransactionTemplate(transactionManager);
        this.claimTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
        this.environment = environment;
        this.maxAttempts = maxAttempts;
        this.claimSeconds = claimSeconds;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${peakdispatch.worker.outbox-delay-ms:500}")
    public void publishBatch() {
        Instant now = Instant.now(clock);
        String owner = UUID.randomUUID().toString();
        List<OutboxRow> publishable = new ArrayList<>();
        for (OutboxRow row : claimBatch(owner, now)) {
            if (row.deadLetterPending()) {
                publishDeadLetter(row, owner, now);
            } else {
                publishable.add(row);
            }
        }
        if (publishable.isEmpty()) return;
        try {
            publishToStream(publishable);
            markPublished(publishable, owner);
            if (metrics != null) publishable.forEach(row -> metrics.outboxEvent("success"));
        } catch (RuntimeException failure) {
            for (OutboxRow row : publishable) handleFailure(row, owner, now, failure);
        }
    }

    private List<OutboxRow> claimBatch(String owner, Instant now) {
        List<OutboxRow> rows = claimTransactions.execute(status -> {
            List<OutboxRow> available = jdbc.query("""
                    SELECT event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                           dedupe_key, payload_json, attempts, last_error_code, created_at
                    FROM outbox_event
                    WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at <= ?
                      AND (claim_until IS NULL OR claim_until <= ?)
                    ORDER BY created_at, event_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new OutboxRow(
                    uuid(rs.getBytes("event_id")), rs.getString("topic"), rs.getString("aggregate_type"),
                    uuid(rs.getBytes("aggregate_id")), rs.getLong("aggregate_version"),
                    rs.getString("dedupe_key"), rs.getString("payload_json"), rs.getInt("attempts"),
                    "OUTBOX_RETRY_EXHAUSTED".equals(rs.getString("last_error_code")),
                    rs.getTimestamp("created_at").toInstant()),
                    Timestamp.from(now), Timestamp.from(now), BATCH_SIZE);
            if (available.isEmpty()) return available;
            Timestamp claimUntil = Timestamp.from(now.plusSeconds(claimSeconds));
            List<Object> parameters = new ArrayList<>(available.size() + 4);
            parameters.add(owner);
            parameters.add(claimUntil);
            available.forEach(row -> parameters.add(bytes(row.eventId())));
            parameters.add(Timestamp.from(now));
            parameters.add(Timestamp.from(now));
            int claimed = jdbc.update("""
                    UPDATE outbox_event SET claim_owner = ?, claim_until = ?
                    WHERE published_at IS NULL AND failed_at IS NULL AND event_id IN (%s)
                      AND next_attempt_at <= ? AND (claim_until IS NULL OR claim_until <= ?)
                    """.formatted(placeholders(available.size())), parameters.toArray());
            if (claimed != available.size()) {
                throw new IllegalStateException("Could not claim the complete Outbox batch");
            }
            return available;
        });
        return rows == null ? List.of() : rows;
    }

    private void publishToStream(List<OutboxRow> rows) {
        List<Object> responses = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (OutboxRow row : rows) {
                Map<String, String> fields = envelope(row);
                Map<byte[], byte[]> encodedFields = new LinkedHashMap<>();
                for (Map.Entry<String, String> field : fields.entrySet()) {
                    encodedFields.put(bytes(field.getKey()), bytes(field.getValue()));
                }
                connection.streamCommands().xAdd(
                        MapRecord.create(bytes(streamKey(row.topic())), encodedFields),
                        RedisStreamCommands.XAddOptions.none());
            }
            return null;
        });
        if (responses == null || responses.size() != rows.size()) {
            throw new IllegalStateException("Redis did not confirm the complete Outbox batch");
        }
        for (Object response : responses) {
            if (response == null) throw new IllegalStateException("Redis returned an empty XADD result");
            if (response instanceof RuntimeException failure) throw failure;
        }
    }

    private void markPublished(List<OutboxRow> rows, String owner) {
        List<Object> parameters = new ArrayList<>(rows.size() + 2);
        parameters.add(Timestamp.from(Instant.now(clock)));
        rows.forEach(row -> parameters.add(bytes(row.eventId())));
        parameters.add(owner);
        int updated = transactions.execute(status -> jdbc.update("""
                UPDATE outbox_event
                SET published_at = ?, attempts = attempts + 1, claim_owner = NULL, claim_until = NULL
                WHERE event_id IN (%s) AND published_at IS NULL AND claim_owner = ?
                """.formatted(placeholders(rows.size())), parameters.toArray()));
        if (updated != rows.size()) {
            throw new IllegalStateException("Could not mark the complete Outbox batch as published");
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private void retry(OutboxRow row, String owner, Instant now) {
        long delaySeconds = Math.min(60, 2L << Math.min(row.attempts(), 5));
        jdbc.update("""
                UPDATE outbox_event
                SET attempts = attempts + 1, next_attempt_at = ?, claim_owner = NULL, claim_until = NULL
                WHERE event_id = ? AND published_at IS NULL AND failed_at IS NULL AND claim_owner = ?
                """, Timestamp.from(now.plusSeconds(delaySeconds)), bytes(row.eventId()), owner);
        if (metrics != null) metrics.outboxEvent("retry");
    }

    private void handleFailure(OutboxRow row, String owner, Instant now, RuntimeException failure) {
        if (row.attempts() + 1 < maxAttempts) {
            retry(row, owner, now);
            log.warn("Outbox publish failed for event {} ({})", row.eventId(), failure.getClass().getSimpleName());
            return;
        }

        publishDeadLetter(row, owner, now);
    }

    private void publishDeadLetter(OutboxRow row, String owner, Instant now) {
        try {
            Map<String, String> deadLetter = new HashMap<>(envelope(row));
            deadLetter.put("failureCode", "OUTBOX_RETRY_EXHAUSTED");
            redis.opsForStream().add(MapRecord.create("pd:" + environment + ":stream:dlq:outbox", deadLetter));
        } catch (RuntimeException deadLetterFailure) {
            long delaySeconds = Math.min(60, 2L << Math.min(row.attempts(), 5));
            jdbc.update("""
                    UPDATE outbox_event
                    SET attempts = attempts + 1, next_attempt_at = ?, last_error_code = 'OUTBOX_RETRY_EXHAUSTED',
                        claim_owner = NULL, claim_until = NULL
                    WHERE event_id = ? AND published_at IS NULL AND failed_at IS NULL AND claim_owner = ?
                    """, Timestamp.from(now.plusSeconds(delaySeconds)), bytes(row.eventId()), owner);
            log.warn("Outbox dead-letter publish failed for event {} ({})", row.eventId(),
                    deadLetterFailure.getClass().getSimpleName());
            return;
        }
        int changed = jdbc.update("""
                UPDATE outbox_event
                SET failed_at = ?, last_error_code = 'OUTBOX_RETRY_EXHAUSTED', attempts = attempts + 1,
                    claim_owner = NULL, claim_until = NULL
                WHERE event_id = ? AND published_at IS NULL AND failed_at IS NULL AND claim_owner = ?
                """, Timestamp.from(now), bytes(row.eventId()), owner);
        if (changed == 1) {
            if (metrics != null) metrics.outboxEvent("dlq");
            log.error("Outbox event {} moved to DLQ after {} attempts", row.eventId(), maxAttempts);
        }
    }

    private static Map<String, String> envelope(OutboxRow row) {
        return Map.of(
                "eventId", row.eventId().toString(),
                "eventType", row.topic(),
                "schemaVersion", "1",
                "aggregateType", row.aggregateType(),
                "aggregateId", row.aggregateId().toString(),
                "aggregateVersion", Long.toString(row.aggregateVersion()),
                "dedupeKey", row.dedupeKey(),
                "outboxCreatedAt", row.createdAt().toString(),
                "payload", SensitiveDataRedactor.redactJson(row.payload()));
    }

    private String streamKey(String eventType) {
        String stream = switch (eventType) {
            case "order.dispatch_requested", "dispatch.no_candidate" -> "dispatch";
            default -> "events";
        };
        return "pd:" + environment + ":stream:" + stream;
    }

    private record OutboxRow(UUID eventId, String topic, String aggregateType, UUID aggregateId,
                             long aggregateVersion, String dedupeKey, String payload, int attempts,
                             boolean deadLetterPending, Instant createdAt) {
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
