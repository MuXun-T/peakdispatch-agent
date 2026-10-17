package com.peakdispatch.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.peakdispatch.adapter.outbox.OutboxPublisher;
import com.peakdispatch.config.RuntimeConfiguration;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RuntimeConfiguration.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxPublisherIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    @BeforeEach
    void holdOtherPendingRows() {
        jdbc.update("""
                UPDATE outbox_event SET claim_owner = 'held-by-test', claim_until = ?
                WHERE published_at IS NULL
                """, Timestamp.from(Instant.now(clock).plusSeconds(60)));
    }

    @Test
    void publishesDispatchEventToNamespacedStreamAndMarksOnlyOwnedClaim() {
        UUID eventId = insertOutbox("order.dispatch_requested", "dispatch:" + UUID.randomUUID(), null);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        List<MapRecord<byte[], byte[], byte[]>> records = new ArrayList<>();
        AtomicInteger pipelineCalls = new AtomicInteger();
        mockSuccessfulPipeline(redis, records, pipelineCalls);
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager, clock, "test", 10, 30);

        publisher.publishBatch();

        assertThat(pipelineCalls).hasValue(1);
        assertThat(records).hasSize(1);
        assertThat(decode(records.getFirst().getStream())).isEqualTo("pd:test:stream:dispatch");
        assertThat(recordFields(records.getFirst())).containsEntry("eventId", eventId.toString())
                .containsEntry("eventType", "order.dispatch_requested")
                .containsEntry("schemaVersion", "1");
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_event WHERE event_id = ?",
                Boolean.class, uuidBytes(eventId))).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE event_id = ?",
                Integer.class, uuidBytes(eventId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT claim_owner FROM outbox_event WHERE event_id = ?",
                String.class, uuidBytes(eventId))).isNull();
    }

    @Test
    void publishesClaimedRowsInOneRedisPipeline() {
        UUID firstEventId = insertOutbox("order.created", "order:" + UUID.randomUUID(), null);
        UUID secondEventId = insertOutbox("order.created", "order:" + UUID.randomUUID(), null);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        List<MapRecord<byte[], byte[], byte[]>> records = new ArrayList<>();
        AtomicInteger pipelineCalls = new AtomicInteger();
        mockSuccessfulPipeline(redis, records, pipelineCalls);
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager, clock, "test", 10, 30);

        publisher.publishBatch();

        assertThat(pipelineCalls).hasValue(1);
        assertThat(records).hasSize(2);
        assertThat(records).extracting(record -> recordFields(record).get("eventId"))
                .containsExactlyInAnyOrder(firstEventId.toString(), secondEventId.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id IN (?, ?) AND published_at IS NOT NULL",
                Integer.class, uuidBytes(firstEventId), uuidBytes(secondEventId))).isEqualTo(2);
    }

    @Test
    void failedPublishReleasesClaimAndSchedulesRetry() {
        UUID eventId = insertOutbox("order.created", "order:" + UUID.randomUUID(), null);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager, clock, "test", 10, 30);

        publisher.publishBatch();

        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, uuidBytes(eventId))).isNull();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE event_id = ?",
                Integer.class, uuidBytes(eventId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, uuidBytes(eventId)).toInstant()).isAfter(Instant.now(clock));
        assertThat(jdbc.queryForObject("SELECT claim_owner FROM outbox_event WHERE event_id = ?",
                String.class, uuidBytes(eventId))).isNull();
    }

    @Test
    void activeClaimIsSkippedUntilItsLeaseExpires() {
        UUID eventId = insertOutbox("order.created", "order:" + UUID.randomUUID(),
                Timestamp.from(Instant.now(clock).plusSeconds(60)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager, clock, "test", 10, 30);

        publisher.publishBatch();

        verify(redis, never()).executePipelined(any(RedisCallback.class));
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, uuidBytes(eventId))).isNull();
        assertThat(jdbc.queryForObject("SELECT claim_owner FROM outbox_event WHERE event_id = ?",
                String.class, uuidBytes(eventId))).isEqualTo("held-by-another-worker");
    }

    @Test
    void exhaustedPublishIsMovedToDeadLetterStreamAndRetainedInMySql() {
        UUID eventId = insertOutbox("order.created", "order:" + UUID.randomUUID(), null);
        jdbc.update("UPDATE outbox_event SET attempts = 9 WHERE event_id = ?", uuidBytes(eventId));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        when(streams.add(any(MapRecord.class))).thenAnswer(invocation -> {
            MapRecord<String, Object, Object> record = invocation.getArgument(0);
            if ("pd:test:stream:events".equals(record.getStream())) {
                throw new RedisConnectionFailureException("unavailable");
            }
            return null;
        });
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager, clock, "test", 10, 30);

        publisher.publishBatch();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<MapRecord<String, Object, Object>> record =
                org.mockito.ArgumentCaptor.forClass(MapRecord.class);
        verify(redis).executePipelined(any(RedisCallback.class));
        verify(streams).add(record.capture());
        assertThat(record.getValue().getStream()).isEqualTo("pd:test:stream:dlq:outbox");
        assertThat(record.getValue().getValue())
                .containsEntry("failureCode", "OUTBOX_RETRY_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE event_id = ?",
                Integer.class, uuidBytes(eventId))).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT failed_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, uuidBytes(eventId))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, uuidBytes(eventId))).isNull();
    }

    @Test
    void unavailableDeadLetterStreamIsRetriedWithoutRepublishingToMainStream() {
        UUID eventId = insertOutbox("order.created", "order:" + UUID.randomUUID(), null);
        jdbc.update("UPDATE outbox_event SET attempts = 9 WHERE event_id = ?", uuidBytes(eventId));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        AtomicBoolean deadLetterAvailable = new AtomicBoolean();
        when(streams.add(any(MapRecord.class))).thenAnswer(invocation -> {
            MapRecord<String, Object, Object> record = invocation.getArgument(0);
            if (deadLetterAvailable.get() && "pd:test:stream:dlq:outbox".equals(record.getStream())) {
                return null;
            }
            throw new RedisConnectionFailureException("unavailable");
        });
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager, clock, "test", 10, 30);

        publisher.publishBatch();

        assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_event WHERE event_id = ?",
                String.class, uuidBytes(eventId))).isEqualTo("OUTBOX_RETRY_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT failed_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, uuidBytes(eventId))).isNull();
        jdbc.update("UPDATE outbox_event SET next_attempt_at = ? WHERE event_id = ?",
                Timestamp.from(Instant.now(clock).minusSeconds(1)), uuidBytes(eventId));
        deadLetterAvailable.set(true);
        clearInvocations(streams);

        publisher.publishBatch();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<MapRecord<String, Object, Object>> record =
                org.mockito.ArgumentCaptor.forClass(MapRecord.class);
        verify(streams).add(record.capture());
        assertThat(record.getValue().getStream()).isEqualTo("pd:test:stream:dlq:outbox");
        assertThat(jdbc.queryForObject("SELECT failed_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, uuidBytes(eventId))).isNotNull();
    }

    private UUID insertOutbox(String topic, String dedupeKey, Timestamp claimUntil) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now(clock).minusSeconds(1);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, topic, aggregate_type, aggregate_id, aggregate_version, dedupe_key,
                     payload_json, created_at, next_attempt_at, claim_owner, claim_until)
                VALUES (?, ?, 'ORDER', ?, 1, ?, ?, ?, ?, ?, ?)
                """, uuidBytes(eventId), topic, uuidBytes(UUID.randomUUID()), dedupeKey, "{}",
                Timestamp.from(now), Timestamp.from(now), claimUntil == null ? null : "held-by-another-worker",
                claimUntil);
        return eventId;
    }

    private static void mockSuccessfulPipeline(StringRedisTemplate redis,
                                               List<MapRecord<byte[], byte[], byte[]>> records,
                                               AtomicInteger pipelineCalls) {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands streams = mock(RedisStreamCommands.class);
        when(connection.streamCommands()).thenReturn(streams);
        when(streams.xAdd(any(MapRecord.class), any(RedisStreamCommands.XAddOptions.class)))
                .thenAnswer(invocation -> {
                    records.add(invocation.getArgument(0));
                    return RecordId.of("1-0");
                });
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            pipelineCalls.incrementAndGet();
            RedisCallback<?> callback = invocation.getArgument(0);
            int previousCommandCount = records.size();
            callback.doInRedis(connection);
            int currentBatchSize = records.size() - previousCommandCount;
            return new ArrayList<>(Collections.nCopies(currentBatchSize, "1-0"));
        });
    }

    private static Map<String, String> recordFields(MapRecord<byte[], byte[], byte[]> record) {
        Map<String, String> fields = new HashMap<>();
        record.getValue().forEach((key, value) -> fields.put(decode(key), decode(value)));
        return fields;
    }

    private static String decode(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
