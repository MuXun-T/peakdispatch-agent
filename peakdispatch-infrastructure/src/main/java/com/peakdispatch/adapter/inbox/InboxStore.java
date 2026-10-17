package com.peakdispatch.adapter.inbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable consumer lease and deduplication state. Each state change is one committed SQL statement. */
@Repository
public class InboxStore {
    private final JdbcTemplate jdbc;

    public InboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Claim claim(String consumerName, UUID eventId, String owner, Instant now, long leaseSeconds) {
        int reclaimed = jdbc.update("""
                UPDATE inbox_message
                SET claim_owner = ?, claim_until = ?, attempts = attempts + 1, last_attempt_at = ?,
                    last_error_code = NULL
                WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING'
                  AND (claim_until IS NULL OR claim_until <= ?)
                """, owner, Timestamp.from(now.plusSeconds(leaseSeconds)), Timestamp.from(now), consumerName,
                uuidBytes(eventId), Timestamp.from(now));
        if (reclaimed == 1) return Claim.CLAIMED;

        Row row = jdbc.query("""
                SELECT status, claim_owner FROM inbox_message WHERE consumer_name = ? AND event_id = ?
                """, (rs, index) -> new Row(rs.getString(1), rs.getString(2)),
                consumerName, uuidBytes(eventId)).stream().findFirst().orElse(null);
        if (row != null) return "PROCESSED".equals(row.status()) || "DEAD_LETTER".equals(row.status())
                ? Claim.PROCESSED : Claim.BUSY;
        try {
            jdbc.update("""
                    INSERT INTO inbox_message
                        (consumer_name, event_id, status, claim_owner, claim_until, attempts, last_attempt_at)
                    VALUES (?, ?, 'PROCESSING', ?, ?, 1, ?)
                    """, consumerName, uuidBytes(eventId), owner, Timestamp.from(now.plusSeconds(leaseSeconds)),
                    Timestamp.from(now));
            return Claim.CLAIMED;
        } catch (DuplicateKeyException concurrentInsert) {
            Row concurrent = jdbc.query("""
                    SELECT status FROM inbox_message WHERE consumer_name = ? AND event_id = ?
                    """, (rs, index) -> new Row(rs.getString(1), null),
                    consumerName, uuidBytes(eventId)).stream().findFirst().orElse(null);
            return concurrent != null && "PROCESSED".equals(concurrent.status())
                    ? Claim.PROCESSED : Claim.BUSY;
        }
    }

    public boolean processed(String consumerName, UUID eventId, String owner, Instant now) {
        return jdbc.update("""
                UPDATE inbox_message SET status = 'PROCESSED', claim_owner = NULL, claim_until = NULL,
                    processed_at = ?, last_error_code = NULL
                WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING' AND claim_owner = ?
                """, Timestamp.from(now), consumerName, uuidBytes(eventId), owner) == 1;
    }

    public int failed(String consumerName, UUID eventId, String owner, String errorCode, Instant now) {
        jdbc.update("""
                UPDATE inbox_message SET claim_owner = ?, claim_until = ?, last_error_code = ?
                WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING' AND claim_owner = ?
                """, owner, Timestamp.from(now), errorCode, consumerName, uuidBytes(eventId), owner);
        return jdbc.queryForObject("SELECT attempts FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                Integer.class, consumerName, uuidBytes(eventId));
    }

    public boolean deadLettered(String consumerName, UUID eventId, String owner, String streamId, Instant now) {
        return jdbc.update("""
                UPDATE inbox_message SET status = 'DEAD_LETTER', claim_owner = NULL, claim_until = NULL,
                    dead_lettered_at = ?, dlq_stream_id = ?
                WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING' AND claim_owner = ?
                """, Timestamp.from(now), streamId, consumerName, uuidBytes(eventId), owner) == 1;
    }

    /** Explicit DLQ replay resets the durable dedupe record before XADD. */
    public boolean requeueDeadLetter(String consumerName, UUID eventId, String owner, Instant now) {
        return jdbc.update("""
                UPDATE inbox_message SET status = 'PROCESSING', claim_owner = ?, claim_until = ?,
                    attempts = 0, processed_at = NULL, dead_lettered_at = NULL, dlq_stream_id = NULL,
                    last_error_code = NULL,
                    last_attempt_at = ?
                WHERE (consumer_name = ? AND event_id = ? AND status = 'DEAD_LETTER')
                   OR (consumer_name = ? AND event_id = ? AND status = 'PROCESSING'
                       AND claim_owner = ? AND claim_until <= ?)
                """, owner, Timestamp.from(now.plusSeconds(30)), Timestamp.from(now), consumerName,
                uuidBytes(eventId), consumerName, uuidBytes(eventId), owner, Timestamp.from(now)) == 1;
    }

    public boolean undoDeadLetterReplay(String consumerName, UUID eventId, Instant now) {
        return jdbc.update("""
                UPDATE inbox_message SET status = 'DEAD_LETTER', claim_owner = NULL, claim_until = NULL,
                    dead_lettered_at = COALESCE(dead_lettered_at, ?)
                WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING'
                  AND claim_owner = 'dlq-replay'
                """, Timestamp.from(now), consumerName, uuidBytes(eventId)) == 1;
    }

    /** Makes a replayed event immediately eligible after its XADD has succeeded. */
    public boolean releaseDeadLetterReplay(String consumerName, UUID eventId, Instant now) {
        return jdbc.update("""
                UPDATE inbox_message SET claim_owner = 'dlq-replay-published', claim_until = ?
                WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING'
                  AND claim_owner = 'dlq-replay'
                """, Timestamp.from(now), consumerName, uuidBytes(eventId)) == 1;
    }

    public enum Claim { CLAIMED, PROCESSED, BUSY }
    private record Row(String status, String owner) { }

    private static byte[] uuidBytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
