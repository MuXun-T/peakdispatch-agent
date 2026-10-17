package com.peakdispatch.api.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.adapter.inbox.InboxStore;
import com.peakdispatch.adapter.inbox.RedisDeadLetterReplayer;
import com.peakdispatch.adapter.redis.RedisOfferDelayQueue;
import com.peakdispatch.adapter.inbox.RedisStreamConsumer;
import com.peakdispatch.adapter.redis.RedisAvailabilityProbe;
import com.peakdispatch.adapter.redis.RedisCourierProjection;
import com.peakdispatch.application.courier.CourierIndexEntry;
import com.peakdispatch.application.courier.CourierProjection;
import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.courier.CourierPresence;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.GenericContainer;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InboxStore.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class RedisRecoveryIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
            .withExposedPorts(6379);

    @Autowired JdbcTemplate jdbc;
    @Autowired InboxStore inbox;

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisCourierProjection projection;

    @BeforeEach
    void connectToRedisContainer() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        projection = new RedisCourierProjection(redis, jdbc, Clock.systemUTC(), "it");
    }

    @AfterEach
    void closeRedisConnection() {
        if (factory != null) factory.destroy();
    }

    @Test
    void luaPresenceGeoFenceCapacityAndLeaseRecoveryAreAtomic() throws Exception {
        Instant now = Instant.now();
        CourierPresence courier = online(UUID.randomUUID(), 8, 1, now, 2, 1, 0, 0,
                30.25, 120.16, "it-region");
        projection.projectSession(courier);
        List<CourierIndexEntry> nearby = projection.nearby("it-region", 30.25, 120.16, 2, 90);
        assertThat(nearby).extracting(CourierIndexEntry::courierId).containsExactly(courier.courierId());

        var pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<CourierProjection.Lease>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                UUID offerId = UUID.randomUUID();
                attempts.add(pool.submit(() -> projection.reserve(courier, offerId, UUID.randomUUID().toString(), 90, 10_000)));
            }
            List<CourierProjection.Lease> acquired = new ArrayList<>();
            for (Future<CourierProjection.Lease> attempt : attempts) {
                CourierProjection.Lease lease = attempt.get();
                if (lease != null) acquired.add(lease);
            }
            assertThat(acquired).hasSize(1);
            assertThat(projection.reserve(courier, UUID.randomUUID(), "full", 90, 10_000)).isNull();

            projection.release(acquired.getFirst());
            CourierProjection.Lease expiring = projection.reserve(courier, UUID.randomUUID(), "expires", 90, 150);
            assertThat(expiring).isNotNull();
            Thread.sleep(250);
            assertThat(projection.reserve(courier, UUID.randomUUID(), "after-expiry", 90, 10_000)).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void redisOfferDelayQueueClaimsAndCompletesDueOfferIdempotently() {
        RedisOfferDelayQueue delays = new RedisOfferDelayQueue(redis, Clock.systemUTC(), "it");
        UUID offerId = UUID.randomUUID();
        delays.schedule(offerId, Instant.now().minusMillis(10));
        delays.schedule(offerId, Instant.now().minusMillis(5));
        List<com.peakdispatch.application.dispatch.OfferDelayQueue.DueOffer> due = delays.claimDue(10, Instant.now());
        assertThat(due).extracting(com.peakdispatch.application.dispatch.OfferDelayQueue.DueOffer::offerId)
                .containsExactly(offerId);
        delays.complete(due.getFirst());
        assertThat(delays.claimDue(10, Instant.now())).isEmpty();
        delays.complete(due.getFirst());
    }

    @Test
    void expiredOfferDelayClaimCanBeReclaimedAndReleaseIsAtomicAndTokenFenced() {
        RedisOfferDelayQueue delays = new RedisOfferDelayQueue(redis, Clock.systemUTC(), "it");
        UUID offerId = UUID.randomUUID();
        Instant now = Instant.now();
        delays.schedule(offerId, now.minusSeconds(1));
        var first = delays.claimDue(1, now).getFirst();
        assertThat(delays.claimDue(1, now)).isEmpty();

        List<String> keys = com.peakdispatch.adapter.redis.RedisKeyspace.offerDelayKeys("it");
        redis.opsForZSet().add(keys.get(1), offerId.toString(), now.minusMillis(1).toEpochMilli());
        var second = delays.claimDue(1, now).getFirst();
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

        delays.complete(first);
        assertThat(delays.claimDue(1, now)).isEmpty();
        delays.release(second, now.minusMillis(1));
        var third = delays.claimDue(1, now).getFirst();
        delays.complete(third);
        assertThat(delays.claimDue(1, now)).isEmpty();
    }

    @Test
    void delayedOfferClaimsRespectBatchLimitAcrossDueAndExpiredItems() {
        RedisOfferDelayQueue delays = new RedisOfferDelayQueue(redis, Clock.systemUTC(), "it");
        Instant now = Instant.now();
        for (int index = 0; index < 3; index++) delays.schedule(UUID.randomUUID(), now.minusSeconds(1));

        var first = delays.claimDue(2, now);
        var second = delays.claimDue(2, now);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream())
                .map(com.peakdispatch.application.dispatch.OfferDelayQueue.DueOffer::offerId).distinct().toList())
                .hasSize(3);
    }

    @Test
    void oldGenerationCannotRecreateGeoAfterSessionFence() {
        Instant now = Instant.now();
        UUID courierId = UUID.randomUUID();
        CourierPresence old = online(courierId, 4, 9, now, 3, 0, 0, 0, 30.25, 120.16, "it-region");
        projection.projectSession(old);
        redis.delete("pd:it:courier:{it:" + courierId + "}:presence");
        CourierPresence nextSession = online(courierId, 5, 0, null, 3, 0, 0, 0, null, null, "it-region");
        projection.projectSession(nextSession, "it-region");
        assertThat(projection.nearby("it-region", 30.25, 120.16, 2, 90)).isEmpty();
        assertThatThrownBy(() -> projection.projectHeartbeat(old)).isInstanceOf(IllegalStateException.class);

        CourierPresence fresh = online(courierId, 5, 1, Instant.now(), 3, 0, 0, 0, 30.251, 120.161, "it-region");
        projection.projectHeartbeat(fresh);
        assertThat(projection.nearby("it-region", 30.25, 120.16, 2, 90))
                .extracting(CourierIndexEntry::presenceGeneration).containsExactly(5L);

        CourierPresence offline = new CourierPresence(courierId, null, CourierOnlineState.OFFLINE,
                6, 0, null, null, null, 3, 0, 0, 0, Instant.now());
        projection.removeSession(offline);
        assertThat(projection.nearby("it-region", 30.25, 120.16, 2, 90)).isEmpty();
        String presenceKey = "pd:it:courier:{it:" + courierId + "}:presence";
        assertThat(redis.opsForHash().get(presenceKey, "online")).isEqualTo("0");
        assertThat(redis.opsForHash().get(presenceKey, "generation")).isEqualTo("6");
        assertThatThrownBy(() -> projection.projectHeartbeat(fresh)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projection.projectSession(fresh)).isInstanceOf(IllegalStateException.class);

        CourierPresence resumed = online(courierId, 7, 0, null, 3, 0, 0, 0, null, null, "it-region");
        projection.projectSession(resumed);
        assertThat(redis.opsForHash().get(presenceKey, "online")).isEqualTo("1");
        assertThat(redis.opsForHash().get(presenceKey, "generation")).isEqualTo("7");
        assertThat(projection.nearby("it-region", 30.25, 120.16, 2, 90)).isEmpty();
    }

    @Test
    void expiredHeartbeatIsRemovedFromGeoAndFenceRejectsDelayedIndexWrite() {
        UUID courierId = UUID.randomUUID();
        CourierPresence first = online(courierId, 12, 1, Instant.now(), 3, 0, 0, 0,
                30.25, 120.16, "it-region");
        projection.projectSession(first);
        String member = courierId.toString();
        String versions = "pd:it:geo:{it:it-region}:versions";
        String heartbeats = "pd:it:geo:{it:it-region}:heartbeats";
        redis.opsForZSet().add(heartbeats, member, Instant.now().minusSeconds(120).toEpochMilli());

        assertThat(projection.nearby("it-region", 30.25, 120.16, 2, 90)).isEmpty();
        assertThat(redis.opsForZSet().score(heartbeats, member)).isNull();
        assertThat(redis.opsForHash().get(versions, member)).isEqualTo("12:2");

        projection.projectHeartbeat(first);
        assertThat(projection.nearby("it-region", 30.25, 120.16, 2, 90)).isEmpty();
        CourierPresence next = online(courierId, 12, 2, Instant.now(), 3, 0, 0, 0,
                30.251, 120.161, "it-region");
        projection.projectHeartbeat(next);
        assertThat(projection.nearby("it-region", 30.25, 120.16, 2, 90))
                .extracting(CourierIndexEntry::sequence).containsExactly(2L);
    }

    @Test
    void reconciliationRepairsSameSequenceCoordinateDrift() {
        UUID courierId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("""
                INSERT INTO courier
                    (id, region_id, online_state, presence_generation, capacity_limit,
                     heartbeat_sequence, latitude, longitude, last_position_at)
                VALUES (?, 'it-region', 'ONLINE', 20, 3, 7, 30.251, 120.161, ?)
                """, uuidBytes(courierId), java.sql.Timestamp.from(now));
        jdbc.update("""
                INSERT INTO courier_capacity
                    (courier_id, capacity_limit, committed_count, reserved_count, version, updated_at)
                VALUES (?, 3, 0, 0, 0, ?)
                """, uuidBytes(courierId), java.sql.Timestamp.from(now));
        CourierPresence redisSnapshot = online(courierId, 20, 7, now, 3, 0, 0, 0,
                30.25, 120.16, "it-region");
        CourierPresence mysqlSnapshot = online(courierId, 20, 7, now, 3, 0, 0, 0,
                30.251, 120.161, "it-region");

        try {
            projection.projectSession(redisSnapshot);
            assertThat(projection.nearby("it-region", 30.251, 120.161, 0.05, 90)).isEmpty();

            projection.reconcile(mysqlSnapshot);

            assertThat(projection.nearby("it-region", 30.251, 120.161, 0.05, 90))
                    .extracting(CourierIndexEntry::courierId).containsExactly(courierId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM redis_projection_reconciliation WHERE courier_id = ?",
                    Integer.class, uuidBytes(courierId))).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM redis_projection_reconciliation WHERE courier_id = ?", uuidBytes(courierId));
            jdbc.update("DELETE FROM courier_capacity WHERE courier_id = ?", uuidBytes(courierId));
            jdbc.update("DELETE FROM courier WHERE id = ?", uuidBytes(courierId));
        }
    }

    @Test
    void reconciliationRestoresOnlineCourierWithInitialHeartbeatSequence() {
        UUID courierId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("""
                INSERT INTO courier
                    (id, region_id, online_state, presence_generation, capacity_limit,
                     heartbeat_sequence, latitude, longitude, last_position_at)
                VALUES (?, 'it-region', 'ONLINE', 1, 3, 0, 30.25, 120.16, ?)
                """, uuidBytes(courierId), java.sql.Timestamp.from(now));
        jdbc.update("""
                INSERT INTO courier_capacity
                    (courier_id, capacity_limit, committed_count, reserved_count, version, updated_at)
                VALUES (?, 3, 0, 0, 0, ?)
                """, uuidBytes(courierId), java.sql.Timestamp.from(now));
        CourierPresence snapshot = online(courierId, 1, 0, now, 3, 0, 0, 0,
                30.25, 120.16, "it-region");

        try {
            projection.reconcile(snapshot);

            assertThat(projection.nearby("it-region", 30.25, 120.16, 0.05, 90))
                    .extracting(CourierIndexEntry::courierId).containsExactly(courierId);
            String presenceKey = "pd:it:courier:{it:" + courierId + "}:presence";
            assertThat(redis.opsForHash().get(presenceKey, "sequence")).isEqualTo("0");
            assertThat(redis.opsForHash().get(presenceKey, "heartbeatMs"))
                    .isEqualTo(Long.toString(now.toEpochMilli()));
        } finally {
            jdbc.update("DELETE FROM redis_projection_reconciliation WHERE courier_id = ?", uuidBytes(courierId));
            jdbc.update("DELETE FROM courier_capacity WHERE courier_id = ?", uuidBytes(courierId));
            jdbc.update("DELETE FROM courier WHERE id = ?", uuidBytes(courierId));
        }
    }

    @Test
    void duplicateEventIdProcessesOnceAndAckFailureRecoversThroughXAutoClaim() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DispatchService dispatch = mock(DispatchService.class);
        CourierRepository couriers = mock(CourierRepository.class);
        CourierProjection projectionPort = mock(CourierProjection.class);
        Map<String, String> event = event(eventId, orderId);
        redis.opsForStream().add(MapRecord.create("pd:it:stream:dispatch", event));
        redis.opsForStream().add(MapRecord.create("pd:it:stream:dispatch", event));

        consumer(dispatch, couriers, projectionPort).consumeBatch();

        verify(dispatch, times(1)).advanceCreatedOrder(orderId);
        assertThat(redis.opsForStream().pending("pd:it:stream:dispatch", "peakdispatch-dispatch-v1")
                .getTotalPendingMessages()).isZero();

        UUID secondEvent = UUID.randomUUID();
        RecordId recordId = redis.opsForStream().add(MapRecord.create("pd:it:stream:dispatch", event(secondEvent, orderId)));
        RedisStreamConsumer crashesAfterCommit = new RedisStreamConsumer(redis, inbox, dispatch,
                couriers, projectionPort, new ObjectMapper(), Clock.systemUTC(), "it") {
            @Override
            protected void ack(String stream, String group, RecordId id) {
                assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE event_id = ?",
                        String.class, uuidBytes(secondEvent))).isEqualTo("PROCESSED");
                throw new IllegalStateException("simulated process failure before XACK");
            }
        };
        crashesAfterCommit.consumeBatch();
        verify(dispatch, times(2)).advanceCreatedOrder(orderId);
        forcePendingIdle("pd:it:stream:dispatch", "peakdispatch-dispatch-v1", recordId);

        consumer(dispatch, couriers, projectionPort).consumeBatch();

        verify(dispatch, times(2)).advanceCreatedOrder(orderId);
        assertThat(redis.opsForStream().pending("pd:it:stream:dispatch", "peakdispatch-dispatch-v1")
                .getTotalPendingMessages()).isZero();
    }

    @Test
    void failedHandlerRemainsPendingThenSucceedsOnXAUTOCLAIMRetry() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DispatchService dispatch = mock(DispatchService.class);
        doThrow(new IllegalStateException("simulated pre-commit failure"))
                .doNothing().when(dispatch).advanceCreatedOrder(orderId);
        CourierRepository couriers = mock(CourierRepository.class);
        CourierProjection projectionPort = mock(CourierProjection.class);
        RecordId recordId = redis.opsForStream().add(MapRecord.create("pd:it:stream:dispatch", event(eventId, orderId)));

        consumer(dispatch, couriers, projectionPort).consumeBatch();
        assertThat(redis.opsForStream().pending("pd:it:stream:dispatch", "peakdispatch-dispatch-v1")
                .getTotalPendingMessages()).isEqualTo(1);
        forcePendingIdle("pd:it:stream:dispatch", "peakdispatch-dispatch-v1", recordId);

        consumer(dispatch, couriers, projectionPort).consumeBatch();

        verify(dispatch, times(2)).advanceCreatedOrder(orderId);
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE event_id = ?",
                String.class, uuidBytes(eventId))).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM inbox_message WHERE event_id = ?",
                Integer.class, uuidBytes(eventId))).isEqualTo(2);
    }

    @Test
    void failedHandlerIsAckedOnlyAfterConsumerDlqWriteSucceeds() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DispatchService dispatch = mock(DispatchService.class);
        doThrow(new IllegalStateException("permanent handler failure"))
                .when(dispatch).advanceCreatedOrder(orderId);
        String stream = "pd:it:stream:dispatch";
        String group = "peakdispatch-dispatch-v1";
        RecordId recordId = redis.opsForStream().add(MapRecord.create(stream, event(eventId, orderId)));

        consumer(dispatch, mock(CourierRepository.class), mock(CourierProjection.class), 2).consumeBatch();
        forcePendingIdle(stream, group, recordId);
        consumer(dispatch, mock(CourierRepository.class), mock(CourierProjection.class), 2).consumeBatch();

        assertThat(redis.opsForStream().pending(stream, group).getTotalPendingMessages()).isZero();
        assertThat(redis.opsForStream().size(com.peakdispatch.adapter.redis.RedisKeyspace
                .consumerDeadLetterKeys("it").getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE event_id = ?", String.class,
                uuidBytes(eventId))).isEqualTo("DEAD_LETTER");
    }

    @Test
    void consumerDlqReplayRequeuesTheEventAndDeduplicatesTheInboxRecord() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DispatchService failing = mock(DispatchService.class);
        doThrow(new IllegalStateException("permanent handler failure"))
                .when(failing).advanceCreatedOrder(orderId);
        String stream = "pd:it:stream:dispatch";
        String group = "peakdispatch-dispatch-v1";
        RecordId recordId = redis.opsForStream().add(MapRecord.create(stream, event(eventId, orderId)));
        consumer(failing, mock(CourierRepository.class), mock(CourierProjection.class), 1).consumeBatch();
        assertThat(redis.opsForStream().size(com.peakdispatch.adapter.redis.RedisKeyspace
                .consumerDeadLetterKeys("it").getFirst())).isEqualTo(1);

        String dlq = com.peakdispatch.adapter.redis.RedisKeyspace.consumerDeadLetterKeys("it").getFirst();
        String replayGroup = "peakdispatch-dlq-replay-v1";
        redis.opsForStream().createGroup(dlq, org.springframework.data.redis.connection.stream.ReadOffset.from("0-0"), replayGroup);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(replayGroup, "replayer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(1),
                org.springframework.data.redis.connection.stream.StreamOffset.create(dlq,
                        org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
        assertThat(records).hasSize(1);
        RedisDeadLetterReplayer replayer = new RedisDeadLetterReplayer(redis, inbox, Clock.systemUTC());
        assertThat(replayer.replay(records.getFirst(), "peakdispatch-dispatch-v1", replayGroup)).isTrue();
        assertThat(replayer.replay(records.getFirst(), "peakdispatch-dispatch-v1", replayGroup)).isFalse();

        DispatchService recovered = mock(DispatchService.class);
        consumer(recovered, mock(CourierRepository.class), mock(CourierProjection.class), 2).consumeBatch();
        verify(recovered).advanceCreatedOrder(orderId);
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE event_id = ?", String.class,
                uuidBytes(eventId))).isEqualTo("PROCESSED");
        assertThat(redis.opsForStream().pending(stream, group).getTotalPendingMessages()).isZero();
    }

    @Test
    void unavailableConsumerDlqLeavesMalformedRecordPending() {
        String stream = "pd:it:stream:dispatch";
        redis.opsForStream().add(MapRecord.create(stream, Map.of("eventType", "order.created")));
        RedisStreamConsumer consumer = new RedisStreamConsumer(redis, inbox, mock(DispatchService.class),
                mock(CourierRepository.class), mock(CourierProjection.class), new ObjectMapper(),
                Clock.systemUTC(), "it", 1) {
            @Override
            protected String writeDeadLetter(MapRecord<String, Object, Object> record, String sourceStream,
                                             String failureCode, UUID eventId) {
                return null;
            }
        };
        consumer.consumeBatch();
        assertThat(redis.opsForStream().pending(stream, "peakdispatch-dispatch-v1").getTotalPendingMessages())
                .isEqualTo(1);
        assertThat(redis.opsForStream().size(com.peakdispatch.adapter.redis.RedisKeyspace
                .consumerDeadLetterKeys("it").getFirst())).isZero();
    }

    @Test
    void xAutoClaimContinuesAcrossPendingPages() {
        String stream = "pd:it:stream:dispatch";
        String group = "peakdispatch-dispatch-v1";
        int messageCount = 125;
        redis.opsForStream().createGroup(stream, org.springframework.data.redis.connection.stream.ReadOffset.from("0-0"),
                group);
        List<RecordId> ids = new ArrayList<>();
        for (int index = 0; index < messageCount; index++) {
            ids.add(redis.opsForStream().add(MapRecord.create(stream,
                    event(UUID.randomUUID(), UUID.randomUUID()))));
        }
        List<MapRecord<String, Object, Object>> delivered = redis.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(group, "crashed-consumer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(messageCount),
                org.springframework.data.redis.connection.stream.StreamOffset.create(stream,
                        org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
        assertThat(delivered).hasSize(messageCount);
        forcePendingIdle(stream, group, ids);

        DispatchService dispatch = mock(DispatchService.class);
        RedisStreamConsumer consumer = consumer(dispatch, mock(CourierRepository.class), mock(CourierProjection.class));
        consumer.consumeBatch();
        consumer.consumeBatch();

        verify(dispatch, times(messageCount)).advanceCreatedOrder(org.mockito.ArgumentMatchers.any());
        assertThat(redis.opsForStream().pending(stream, group).getTotalPendingMessages()).isZero();
    }

    @Test
    void redisAvailabilityProbeFailsClosedWhenRedisIsUnavailable() {
        LettuceConnectionFactory unavailableFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1));
        unavailableFactory.afterPropertiesSet();
        try {
            StringRedisTemplate unavailable = new StringRedisTemplate(unavailableFactory);
            unavailable.afterPropertiesSet();
            assertThat(new RedisAvailabilityProbe(unavailable).available()).isFalse();
        } finally {
            unavailableFactory.destroy();
        }
    }

    private RedisStreamConsumer consumer(DispatchService dispatch, CourierRepository couriers,
                                         CourierProjection projectionPort) {
        return consumer(dispatch, couriers, projectionPort, 5);
    }

    private RedisStreamConsumer consumer(DispatchService dispatch, CourierRepository couriers,
                                         CourierProjection projectionPort, int maxAttempts) {
        return new RedisStreamConsumer(redis, inbox, dispatch, couriers, projectionPort,
                new ObjectMapper(), Clock.systemUTC(), "it", maxAttempts);
    }

    private void forcePendingIdle(String stream, String group, RecordId id) {
        redis.execute((RedisCallback<Object>) connection -> connection.execute("XCLAIM",
                bytes(stream), bytes(group), bytes("restart-simulator"), bytes("0"), bytes(id.getValue()),
                bytes("IDLE"), bytes("6000")));
    }

    private void forcePendingIdle(String stream, String group, List<RecordId> ids) {
        List<byte[]> args = new ArrayList<>();
        args.add(bytes(stream));
        args.add(bytes(group));
        args.add(bytes("restart-simulator"));
        args.add(bytes("0"));
        ids.forEach(id -> args.add(bytes(id.getValue())));
        args.add(bytes("IDLE"));
        args.add(bytes("6000"));
        redis.execute((RedisCallback<Object>) connection -> connection.execute("XCLAIM", args.toArray(byte[][]::new)));
    }

    private static Map<String, String> event(UUID eventId, UUID orderId) {
        Map<String, String> event = new HashMap<>();
        event.put("eventId", eventId.toString());
        event.put("eventType", "order.created");
        event.put("schemaVersion", "1");
        event.put("aggregateType", "ORDER");
        event.put("aggregateId", orderId.toString());
        event.put("aggregateVersion", "1");
        event.put("dedupeKey", "order:" + orderId + ":created");
        event.put("payload", "{}");
        return event;
    }

    private static CourierPresence online(UUID id, long generation, long sequence, Instant heartbeat,
                                           int capacity, int committed, int reserved, long capacityVersion,
                                           Double latitude, Double longitude, String region) {
        return new CourierPresence(id, region, CourierOnlineState.ONLINE, generation, sequence,
                latitude, longitude, heartbeat, capacity, committed, reserved, capacityVersion,
                heartbeat == null ? Instant.now() : heartbeat);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] uuidBytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
