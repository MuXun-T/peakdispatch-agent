package com.peakdispatch.adapter.mysql;

import com.peakdispatch.application.observability.PeakDispatchMetrics;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'API'")
public class RetentionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);
    private static final Set<String> TASKS = Set.of("location_detail", "agent_run_detail", "audit_data");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration locationRetention;
    private final Duration agentRetention;
    private final Duration auditRetention;
    private final int retryAttempts;
    private final PeakDispatchMetrics metrics;

    public RetentionCleanupService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock,
                                   @Value("${peakdispatch.retention.location-detail:PT24H}") Duration locationRetention,
                                   @Value("${peakdispatch.retention.agent-run:P30D}") Duration agentRetention,
                                   @Value("${peakdispatch.retention.audit:P90D}") Duration auditRetention,
                                   @Value("${peakdispatch.retention.retry-attempts:3}") int retryAttempts) {
        this(jdbc, transactionManager, clock, locationRetention, agentRetention, auditRetention, retryAttempts, null);
    }

    @Autowired
    public RetentionCleanupService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock,
                                   @Value("${peakdispatch.retention.location-detail:PT24H}") Duration locationRetention,
                                   @Value("${peakdispatch.retention.agent-run:P30D}") Duration agentRetention,
                                   @Value("${peakdispatch.retention.audit:P90D}") Duration auditRetention,
                                   @Value("${peakdispatch.retention.retry-attempts:3}") int retryAttempts,
                                   ObjectProvider<PeakDispatchMetrics> metricsProvider) {
        if (locationRetention.isNegative() || locationRetention.isZero() || agentRetention.isNegative()
                || agentRetention.isZero() || auditRetention.isNegative() || auditRetention.isZero()
                || retryAttempts < 1) throw new IllegalArgumentException("Retention settings must be positive");
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.locationRetention = locationRetention;
        this.agentRetention = agentRetention;
        this.auditRetention = auditRetention;
        this.retryAttempts = retryAttempts;
        this.metrics = metricsProvider == null ? null : metricsProvider.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${peakdispatch.retention.run-delay-ms:86400000}",
            initialDelayString = "${peakdispatch.retention.initial-delay-ms:86400000}")
    public void runCleanup() {
        Instant now = Instant.now(clock);
        executeTask("location_detail", now.minus(locationRetention), this::deleteExpiredLocationDetails);
        executeTask("agent_run_detail", now.minus(agentRetention), this::pruneExpiredAgentDetails);
        executeTask("audit_data", now.minus(auditRetention), () -> jdbc.update(
                "UPDATE audit_log SET details_redacted = NULL WHERE created_at < ? AND details_redacted IS NOT NULL",
                Timestamp.from(now.minus(auditRetention))));
    }

    public int executeTask(String taskName, Instant cutoff, IntSupplier cleanup) {
        if (!TASKS.contains(taskName)) throw new IllegalArgumentException("Unknown retention cleanup task");
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now(clock);
        jdbc.update("""
                INSERT INTO retention_cleanup_run
                    (run_id, task_name, status, cutoff_at, attempt_count, cleaned_rows, started_at)
                VALUES (?, ?, 'RUNNING', ?, 0, 0, ?)
                """, bytes(runId), taskName, Timestamp.from(cutoff), Timestamp.from(started));

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            int currentAttempt = attempt;
            try {
                Integer result = transactions.execute(status -> {
                    int changed = cleanup.getAsInt();
                    jdbc.update("""
                            UPDATE retention_cleanup_run
                            SET status = 'COMPLETED', attempt_count = ?, cleaned_rows = ?, error_code = NULL,
                                completed_at = ? WHERE run_id = ?
                            """, currentAttempt, Math.max(0, changed), Timestamp.from(Instant.now(clock)), bytes(runId));
                    return changed;
                });
                if (metrics != null) metrics.retention(taskName, "success", result == null ? 0 : result);
                return result == null ? 0 : result;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                String state = attempt == retryAttempts ? "FAILED" : "RETRYING";
                jdbc.update("""
                        UPDATE retention_cleanup_run
                        SET status = ?, attempt_count = ?, error_code = ?, completed_at = ?
                        WHERE run_id = ?
                        """, state, currentAttempt, failure.getClass().getSimpleName(),
                        currentAttempt == retryAttempts ? Timestamp.from(Instant.now(clock)) : null, bytes(runId));
                log.warn("Retention cleanup {} attempt {} failed ({})", taskName, attempt,
                        failure.getClass().getSimpleName());
            }
        }
        log.error("Retention cleanup {} exhausted {} attempts", taskName, retryAttempts);
        if (metrics != null) metrics.retention(taskName, "failed", 0);
        if (lastFailure != null) return 0;
        return 0;
    }

    private int deleteExpiredLocationDetails() {
        Instant cutoff = Instant.now(clock).minus(locationRetention);
        return jdbc.update("""
                DELETE FROM dispatch_candidate_decision
                WHERE attempt_id IN (
                    SELECT id FROM dispatch_attempt WHERE COALESCE(finished_at, started_at) < ?
                )
                """, Timestamp.from(cutoff));
    }

    private int pruneExpiredAgentDetails() {
        Instant cutoff = Instant.now(clock).minus(agentRetention);
        Timestamp value = Timestamp.from(cutoff);
        int events = jdbc.update("""
                DELETE FROM agent_run_event
                WHERE run_id IN (SELECT id FROM agent_run WHERE started_at < ?)
                """, value);
        int tools = jdbc.update("""
                UPDATE agent_tool_call SET result_ref = NULL, result_json = NULL
                WHERE run_id IN (SELECT id FROM agent_run WHERE started_at < ?)
                  AND (result_ref IS NOT NULL OR result_json IS NOT NULL)
                """, value);
        int runs = jdbc.update("""
                UPDATE agent_run
                SET checkpoint_json = NULL, input_json = NULL, input_redacted = '[EXPIRED]'
                WHERE started_at < ? AND (checkpoint_json IS NOT NULL OR input_json IS NOT NULL
                    OR input_redacted IS NOT NULL)
                """, value);
        int actions = jdbc.update("""
                UPDATE agent_action SET params_json = '{}', result_ref = NULL
                WHERE created_at < ? AND (params_json <> '{}' OR result_ref IS NOT NULL)
                """, value);
        return events + tools + runs + actions;
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
