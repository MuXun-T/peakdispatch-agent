package com.peakdispatch.application.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class PeakDispatchMetrics {
    private static final Set<String> OUTCOMES = Set.of("success", "rejected", "rollback", "created", "accepted",
            "declined", "timeout", "no_candidate", "contended", "unavailable", "error", "retry", "dlq",
            "expired", "conflict", "hit", "no_evidence", "referenced", "completed", "failed");
    private final MeterRegistry registry;
    private final AtomicLong outboxPending;
    private final AtomicLong outboxOldestAgeSeconds;
    private final AtomicLong streamPending;
    private final AtomicLong streamBacklog;
    private final AtomicLong streamRetries;
    private final AtomicLong streamDlq;
    private final AtomicLong approvalWaiting;
    private final AtomicLong capacityDrift;
    private final AtomicLong heartbeatExpiredCurrent;

    public PeakDispatchMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.outboxPending = gauge("peakdispatch.outbox.pending");
        this.outboxOldestAgeSeconds = gauge("peakdispatch.outbox.oldest.age");
        this.streamPending = gauge("peakdispatch.stream.pending");
        this.streamBacklog = gauge("peakdispatch.stream.backlog");
        this.streamRetries = gauge("peakdispatch.stream.retries");
        this.streamDlq = gauge("peakdispatch.stream.dlq");
        this.approvalWaiting = gauge("peakdispatch.approval.waiting");
        this.capacityDrift = gauge("peakdispatch.capacity.reconciliation.drift");
        this.heartbeatExpiredCurrent = gauge("peakdispatch.heartbeat.expired.current");
        Timer.builder("peakdispatch.dispatch.decision")
                .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram().register(registry);
        Timer.builder("peakdispatch.dispatch.event-to-result")
                .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram().register(registry);
        Timer.builder("peakdispatch.stream.queue-wait")
                .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram().register(registry);
        DistributionSummary.builder("peakdispatch.dispatch.candidates")
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }

    public void rateLimited(String dimension) {
        increment("peakdispatch.rate_limited", "dimension", dimension);
    }

    public void dispatchDecision(Duration duration, int candidates, String outcome) {
        long elapsedNanos = Math.max(0, duration.toNanos());
        Timer.builder("peakdispatch.dispatch.decision").register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        Timer.builder("peakdispatch.dispatch.decision").tag("outcome", safe(outcome))
                .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram().register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        DistributionSummary.builder("peakdispatch.dispatch.candidates").register(registry)
                .record(Math.max(0, candidates));
        DistributionSummary.builder("peakdispatch.dispatch.candidates").tag("outcome", safe(outcome))
                .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(Math.max(0, candidates));
        increment("peakdispatch.dispatch.decisions", "outcome", outcome);
        if ("NO_CANDIDATE".equalsIgnoreCase(outcome)) {
            increment("peakdispatch.dispatch.no_candidate", "result", "true");
        }
    }

    public void dispatchEventToResult(Duration duration) {
        Timer.builder("peakdispatch.dispatch.event-to-result")
                .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram().register(registry)
                .record(Math.max(0, duration.toNanos()), TimeUnit.NANOSECONDS);
    }

    public void streamQueueWait(Duration duration, String eventType) {
        Timer.builder("peakdispatch.stream.queue-wait").register(registry)
                .record(Math.max(0, duration.toNanos()), TimeUnit.NANOSECONDS);
        Timer.builder("peakdispatch.stream.queue-wait").tag("event", safe(eventType))
                .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram().register(registry)
                .record(Math.max(0, duration.toNanos()), TimeUnit.NANOSECONDS);
    }

    public void offer(String outcome) { increment("peakdispatch.offer.events", "outcome", outcome); }
    public void lease(String outcome) { increment("peakdispatch.lease.events", "outcome", outcome); }
    public void dbCasConflict() { increment("peakdispatch.db.cas.conflicts", "type", "dispatch"); }
    public void streamEvent(String outcome) { increment("peakdispatch.stream.events", "outcome", outcome); }
    public void outboxEvent(String outcome) { increment("peakdispatch.outbox.events", "outcome", outcome); }
    public void heartbeatExpired(long count) { add("peakdispatch.heartbeat.expired.events", "source", "redis", count); }
    public void heartbeatExpiredCurrent(long count) { heartbeatExpiredCurrent.set(Math.max(0, count)); }
    public void redisCall(Duration duration, boolean success) {
        Timer.builder("peakdispatch.redis.command").tag("outcome", success ? "success" : "error")
                .publishPercentiles(0.5, 0.95, 0.99).register(registry)
                .record(Math.max(0, duration.toNanos()), TimeUnit.NANOSECONDS);
        if (!success) increment("peakdispatch.redis.errors", "operation", "command");
    }
    public void agentTool(String tool, String outcome, Duration duration) {
        String safeTool = tool != null && tool.matches("[A-Za-z0-9_-]{1,64}") ? tool : "other";
        Timer.builder("peakdispatch.agent.tool.duration").tag("tool", safeTool).tag("outcome", safe(outcome))
                .publishPercentiles(0.5, 0.95, 0.99).register(registry)
                .record(Math.max(0, duration.toNanos()), TimeUnit.NANOSECONDS);
        increment("peakdispatch.agent.tool.calls", "outcome", outcome);
    }
    public void approval(String outcome) { increment("peakdispatch.approval.events", "outcome", outcome); }
    public void knowledgeSearch(int hits, int citations, boolean noEvidence) {
        add("peakdispatch.knowledge.search.hits", "kind", "documents", hits);
        add("peakdispatch.knowledge.citations", "kind", "returned", citations);
        increment("peakdispatch.knowledge.no_evidence", "result", Boolean.toString(noEvidence));
    }
    public void modelTokens(String direction, long count) {
        if (!Set.of("input", "output").contains(direction) || count <= 0) return;
        add("peakdispatch.model.tokens", "direction", direction, count);
    }
    public void modelHttpResponse(int statusCode) {
        if (statusCode < 100 || statusCode > 599) return;
        increment("peakdispatch.model.http.responses", "status", Integer.toString(statusCode));
    }
    public void modelCall(String outcome, Duration duration) {
        Timer.builder("peakdispatch.model.call.duration").tag("outcome", safe(outcome))
                .publishPercentiles(0.5, 0.95, 0.99).register(registry)
                .record(Math.max(0, duration.toNanos()), TimeUnit.NANOSECONDS);
        increment("peakdispatch.model.calls", "outcome", outcome);
    }
    public void retention(String task, String outcome, long rows) {
        add("peakdispatch.retention.rows", "task", task, rows);
        increment("peakdispatch.retention.runs", "outcome", outcome);
    }

    public void outboxState(long pending, long oldestAgeSeconds) {
        outboxPending.set(Math.max(0, pending));
        outboxOldestAgeSeconds.set(Math.max(0, oldestAgeSeconds));
    }
    public void streamState(long pending, long backlog) {
        streamState(pending, backlog, streamRetries.get(), streamDlq.get());
    }
    public void streamState(long pending, long backlog, long retries, long dlq) {
        streamPending.set(Math.max(0, pending));
        streamBacklog.set(Math.max(0, backlog));
        streamRetries.set(Math.max(0, retries));
        streamDlq.set(Math.max(0, dlq));
    }
    public void approvalWaiting(long waiting) { approvalWaiting.set(Math.max(0, waiting)); }
    public void capacityDrift(long value) { capacityDrift.set(value); }

    private AtomicLong gauge(String name) {
        AtomicLong value = new AtomicLong();
        registry.gauge(name, value);
        return value;
    }

    private void increment(String name, String tag, String value) {
        Counter.builder(name).tag(tag, safe(value)).register(registry).increment();
    }

    private void add(String name, String tag, String value, long amount) {
        if (amount <= 0) return;
        Counter.builder(name).tag(tag, safe(value)).register(registry).increment(amount);
    }

    private static String safe(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : "other";
    }
}
