package com.peakdispatch.application.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PeakDispatchMetricsTest {
    @Test
    void registersOperationalGaugesAndRecordsDispatchDistributions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PeakDispatchMetrics metrics = new PeakDispatchMetrics(registry);

        metrics.outboxState(7, 12);
        metrics.streamState(3, 18, 2, 1);
        metrics.dispatchDecision(Duration.ofMillis(250), 4, "NO_CANDIDATE");
        metrics.streamQueueWait(Duration.ofMillis(80), "order-created");

        assertThat(registry.get("peakdispatch.outbox.pending").gauge().value()).isEqualTo(7);
        assertThat(registry.get("peakdispatch.outbox.oldest.age").gauge().value()).isEqualTo(12);
        assertThat(registry.get("peakdispatch.stream.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("peakdispatch.stream.backlog").gauge().value()).isEqualTo(18);
        assertThat(registry.get("peakdispatch.dispatch.decision").timer().count()).isEqualTo(1);
        assertThat(registry.get("peakdispatch.dispatch.decision").tag("outcome", "NO_CANDIDATE")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("peakdispatch.dispatch.candidates").summary().count()).isEqualTo(1);
        assertThat(registry.get("peakdispatch.stream.queue-wait").timer().count()).isEqualTo(1);
        assertThat(registry.get("peakdispatch.stream.queue-wait").tag("event", "order-created")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("peakdispatch.dispatch.no_candidate").counter().count()).isEqualTo(1);
    }
}
