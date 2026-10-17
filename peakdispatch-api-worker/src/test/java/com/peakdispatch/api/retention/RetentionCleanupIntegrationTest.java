package com.peakdispatch.api.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakdispatch.adapter.mysql.RetentionCleanupService;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RetentionCleanupService.class, RetentionCleanupIntegrationTest.FixedClockConfiguration.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RetentionCleanupIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionCleanupService cleanup;

    @Test
    void cleanup_removesExpiredRunDetailsAndAuditButKeepsUnexpiredRowsAndSummaries() {
        UUID oldSession = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        UUID newSession = UUID.randomUUID();
        UUID newRun = UUID.randomUUID();
        insertSession(oldSession, NOW.minusSeconds(40L * 86400));
        insertRun(oldRun, oldSession, NOW.minusSeconds(31L * 86400));
        insertRunEvent(oldRun, "old sensitive message");
        insertSession(newSession, NOW.minusSeconds(10L * 86400));
        insertRun(newRun, newSession, NOW.minusSeconds(29L * 86400));
        insertRunEvent(newRun, "recent message");
        UUID oldAudit = insertAudit(NOW.minusSeconds(91L * 86400));
        UUID newAudit = insertAudit(NOW.minusSeconds(89L * 86400));
        int completedAgentCleanupRuns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM retention_cleanup_run
                WHERE task_name = 'agent_run_detail' AND status = 'COMPLETED'
                """, Integer.class);

        cleanup.runCleanup();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id = ?", Integer.class,
                bytes(oldRun))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id = ?", Integer.class,
                bytes(newRun))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_redacted FROM agent_run WHERE id = ?", String.class,
                bytes(oldRun))).isEqualTo("[EXPIRED]");
        assertThat(jdbc.queryForObject("SELECT summary_redacted FROM agent_session WHERE id = ?", String.class,
                bytes(oldSession))).isEqualTo("session summary retained");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id = ?", Integer.class,
                bytes(oldAudit))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE id = ?", String.class,
                bytes(oldAudit))).isEqualTo("actor");
        assertThat(jdbc.queryForObject("SELECT details_redacted FROM audit_log WHERE id = ?", String.class,
                bytes(oldAudit))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id = ?", Integer.class,
                bytes(newAudit))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM retention_cleanup_run
                WHERE task_name = 'agent_run_detail' AND status = 'COMPLETED'
                """, Integer.class)).isEqualTo(completedAgentCleanupRuns + 1);
    }

    @Test
    void locationCandidateDetails_expireAfter24HoursAndKeepRecentRows() {
        UUID expiredAttempt = insertLocationCandidate(NOW.minusSeconds(25L * 3600), UUID.randomUUID());
        UUID recentAttempt = insertLocationCandidate(NOW.minusSeconds(23L * 3600), UUID.randomUUID());

        cleanup.runCleanup();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_candidate_decision WHERE attempt_id = ?",
                Integer.class, bytes(expiredAttempt))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_candidate_decision WHERE attempt_id = ?",
                Integer.class, bytes(recentAttempt))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_attempt WHERE id = ?",
                Integer.class, bytes(expiredAttempt))).isEqualTo(1);
    }

    @Test
    void cleanup_retriesTransientFailureAndRecordsExhaustion() {
        AtomicInteger calls = new AtomicInteger();
        Instant locationCutoff = NOW.minusSeconds(86400)
                .minusSeconds(UUID.randomUUID().getLeastSignificantBits() & 0xffff);
        int rows = cleanup.executeTask("location_detail", locationCutoff, () -> {
            if (calls.getAndIncrement() < 2) throw new IllegalStateException("transient failure");
            return 7;
        });

        assertThat(rows).isEqualTo(7);
        assertThat(calls).hasValue(3);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM retention_cleanup_run WHERE task_name = 'location_detail' AND cutoff_at = ?
                """, String.class, Timestamp.from(locationCutoff))).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM retention_cleanup_run WHERE task_name = 'location_detail' AND cutoff_at = ?
                """, Integer.class, Timestamp.from(locationCutoff))).isEqualTo(3);

        Instant auditCutoff = NOW.minusSeconds(90L * 86400)
                .minusSeconds(UUID.randomUUID().getLeastSignificantBits() & 0xffff);
        cleanup.executeTask("audit_data", auditCutoff, () -> {
            throw new IllegalArgumentException("persistent failure");
        });
        assertThat(jdbc.queryForObject("""
                SELECT status FROM retention_cleanup_run WHERE task_name = 'audit_data' AND cutoff_at = ?
                """, String.class, Timestamp.from(auditCutoff))).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM retention_cleanup_run WHERE task_name = 'audit_data' AND cutoff_at = ?
                """, Integer.class, Timestamp.from(auditCutoff))).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT error_code FROM retention_cleanup_run WHERE task_name = 'audit_data' AND cutoff_at = ?
                """, String.class, Timestamp.from(auditCutoff))).isEqualTo("IllegalArgumentException");
    }

    private void insertSession(UUID id, Instant started) {
        jdbc.update("""
                INSERT INTO agent_session (id, owner_id, region_scope, summary_redacted, created_at, updated_at, expires_at)
                VALUES (?, 'retention-test', '[]', 'session summary retained', ?, ?, ?)
                """, bytes(id), Timestamp.from(started), Timestamp.from(started), Timestamp.from(started.plusSeconds(30L * 86400)));
    }

    private void insertRun(UUID id, UUID sessionId, Instant started) {
        jdbc.update("""
                INSERT INTO agent_run (id, session_id, owner_id, status, started_at, updated_at, last_sequence,
                    checkpoint_json, input_redacted, input_json, input_hash)
                VALUES (?, ?, 'retention-test', 'COMPLETED', ?, ?, 1, '{"summary":"preserve"}',
                    'sensitive input', '{"message":"sensitive"}', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
                """, bytes(id), bytes(sessionId), Timestamp.from(started), Timestamp.from(started));
    }

    private void insertRunEvent(UUID runId, String message) {
        jdbc.update("""
                INSERT INTO agent_run_event (run_id, sequence, event_type, payload_redacted, created_at)
                VALUES (?, 1, 'message', ?, ?)
                """, bytes(runId), "{\"content\":\"" + message + "\"}", Timestamp.from(NOW.minusSeconds(1000)));
    }

    private UUID insertAudit(Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision, created_at)
                VALUES (?, 'actor', 'TEST', 'TEST', 'target', 'ALLOWED', ?)
                """, bytes(id), Timestamp.from(createdAt));
        return id;
    }

    private UUID insertLocationCandidate(Instant startedAt, UUID courierId) {
        UUID merchantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO merchant (id, region_id, active, prep_state, version, avg_prep_seconds, updated_at)
                VALUES (?, 'retention-region', TRUE, 'OPEN', 0, 900, ?)
                """, bytes(merchantId), Timestamp.from(startedAt));
        jdbc.update("""
                INSERT INTO delivery_order (id, merchant_id, region_id, pickup_lat, pickup_lon, dropoff_lat,
                    dropoff_lon, prep_eta, deadline, status, version, dispatch_round, created_at, updated_at)
                VALUES (?, ?, 'retention-region', 30.25, 120.16, 30.27, 120.18, ?, ?, 'WAITING_DISPATCH',
                    1, 1, ?, ?)
                """, bytes(orderId), bytes(merchantId), Timestamp.from(startedAt.plusSeconds(3600)),
                Timestamp.from(startedAt.plusSeconds(7200)), Timestamp.from(startedAt), Timestamp.from(startedAt));
        jdbc.update("""
                INSERT INTO courier (id, online_state, capacity_limit, presence_generation, version)
                VALUES (?, 'OFFLINE', 3, 0, 0)
                """, bytes(courierId));
        jdbc.update("""
                INSERT INTO dispatch_attempt (id, order_id, round_no, order_version, estimator_version, outcome,
                    candidate_count, started_at, finished_at)
                VALUES (?, ?, 1, 1, 'retention-test', 'NO_CANDIDATE', 1, ?, ?)
                """, bytes(attemptId), bytes(orderId), Timestamp.from(startedAt), Timestamp.from(startedAt));
        jdbc.update("""
                INSERT INTO dispatch_candidate_decision (attempt_id, courier_id, eligible, reason_code, feature_snapshot)
                VALUES (?, ?, TRUE, NULL, '{"position":"redactable"}')
                """, bytes(attemptId), bytes(courierId));
        return attemptId;
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean Clock retentionTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
