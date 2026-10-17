package com.peakdispatch.api.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakdispatch.adapter.inbox.InboxStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InboxStore.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboxStoreIntegrationTest {
    @Autowired InboxStore inbox;
    @Autowired JdbcTemplate jdbc;

    @Test
    void duplicateEventIsProcessedOnceAndOnlyTheOwnerCanCompleteIt() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        assertThat(inbox.claim("consumer-v1", eventId, "worker-a", now, 30))
                .isEqualTo(InboxStore.Claim.CLAIMED);
        assertThat(inbox.claim("consumer-v1", eventId, "worker-b", now, 30))
                .isEqualTo(InboxStore.Claim.BUSY);
        assertThat(inbox.processed("consumer-v1", eventId, "worker-b", now)).isFalse();
        assertThat(inbox.processed("consumer-v1", eventId, "worker-a", now)).isTrue();
        assertThat(inbox.claim("consumer-v1", eventId, "worker-c", now.plusSeconds(1), 30))
                .isEqualTo(InboxStore.Claim.PROCESSED);
        assertThat(jdbc.queryForObject("SELECT attempts FROM inbox_message WHERE event_id = ?",
                Integer.class, uuidBytes(eventId))).isEqualTo(1);
    }

    @Test
    void expiredInboxClaimCanBeRetriedAfterHandlerFailure() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        inbox.claim("consumer-v1", eventId, "worker-a", now, 30);
        inbox.failed("consumer-v1", eventId, "worker-a", "HANDLER_FAILURE", now.plusMillis(1));

        assertThat(inbox.claim("consumer-v1", eventId, "worker-b", now.plusSeconds(1), 30))
                .isEqualTo(InboxStore.Claim.CLAIMED);
        assertThat(jdbc.queryForObject("SELECT attempts FROM inbox_message WHERE event_id = ?",
                Integer.class, uuidBytes(eventId))).isEqualTo(2);
    }

    @Test
    void deadLetterReplayTakesInboxLeaseBeforeRequeueAndCanRetryAfterExpiredLease() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        inbox.claim("consumer-v1", eventId, "worker-a", now, 30);
        inbox.deadLettered("consumer-v1", eventId, "worker-a", "9-1", now.plusMillis(1));

        assertThat(inbox.requeueDeadLetter("consumer-v1", eventId, "dlq-replay", now.plusSeconds(1))).isTrue();
        assertThat(inbox.requeueDeadLetter("consumer-v1", eventId, "second-replayer", now.plusSeconds(2))).isFalse();
        assertThat(inbox.undoDeadLetterReplay("consumer-v1", eventId, now.plusSeconds(3))).isTrue();
        assertThat(inbox.requeueDeadLetter("consumer-v1", eventId, "dlq-replay", now.plusSeconds(4))).isTrue();
        assertThat(inbox.releaseDeadLetterReplay("consumer-v1", eventId, now.plusSeconds(4))).isTrue();
        assertThat(inbox.requeueDeadLetter("consumer-v1", eventId, "duplicate-replayer", now.plusSeconds(5))).isFalse();
    }

    private static byte[] uuidBytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
