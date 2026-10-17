package com.peakdispatch.api.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peakdispatch.application.courier.CourierPresenceService;
import com.peakdispatch.application.courier.CourierProjection;
import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.dispatch.DispatchOfferConflictException;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.application.dispatch.DispatchReadiness;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.application.dispatch.RedisAvailability;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.adapter.inbox.InboxStore;
import com.peakdispatch.adapter.inbox.RedisDeadLetterReplayer;
import com.peakdispatch.adapter.inbox.RedisStreamConsumer;
import com.peakdispatch.adapter.outbox.OutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.config.DemoDataInitializer;
import com.peakdispatch.config.RedisProjectionRecoveryWorker;
import com.peakdispatch.domain.order.DeliveryOrder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real MySQL 8.4 + Redis 7 concurrency and recovery suite. No H2 or Mockito
 * bean is used by this class. It is skipped by Testcontainers when Docker is
 * unavailable and therefore cannot be reported as a real-infrastructure pass.
 */
@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealMySqlRedisConcurrencyIntegrationTest {
    private static final String DEMO_PASSWORD = "real-infra-test-password";
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.4"))
            .withDatabaseName("peakdispatch")
            .withUsername("peakdispatch")
            .withPassword("peakdispatch-test-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void realInfrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:1");
        registry.add("management.health.elasticsearch.enabled", () -> "false");
        registry.add("peakdispatch.runtime.role", () -> "API");
        registry.add("peakdispatch.dispatch.redis-gate-enabled", () -> "true");
        registry.add("peakdispatch.redis.environment", () -> "real-it");
        registry.add("peakdispatch.demo.users.merchant.password", () -> DEMO_PASSWORD);
        registry.add("peakdispatch.demo.users.courier.password", () -> DEMO_PASSWORD);
        registry.add("peakdispatch.demo.users.ops-viewer.password", () -> DEMO_PASSWORD);
        registry.add("peakdispatch.demo.users.ops-approver.password", () -> DEMO_PASSWORD);
        registry.add("peakdispatch.demo.users.ops-approver-backup.password", () -> DEMO_PASSWORD);
        registry.add("peakdispatch.demo.users.ops-admin.password", () -> DEMO_PASSWORD);
    }

    @Autowired OrderApplicationService orders;
    @Autowired CourierPresenceService presence;
    @Autowired DispatchService dispatch;
    @Autowired DispatchRepository dispatchRepository;
    @Autowired CourierProjection projection;
    @Autowired CourierRepository couriers;
    @Autowired InboxStore inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RedisAvailability redisAvailability;
    @Autowired DispatchReadiness dispatchReadiness;
    @Autowired StringRedisTemplate redis;
    private final List<UUID> testOrders = new java.util.ArrayList<>();

    @AfterEach
    void resetCourier() {
        for (UUID orderId : testOrders) {
            try {
                DeliveryOrder current = orders.get(orderId);
                if (current.status() != com.peakdispatch.domain.order.OrderStatus.CANCELLED
                        && current.status() != com.peakdispatch.domain.order.OrderStatus.DELIVERED) {
                    orders.cancel(orderId, current.version(), "real_test_cleanup", "merchant-test");
                }
            } catch (RuntimeException ignored) {
                // Cleanup must not hide the real-infrastructure assertion.
            }
        }
        testOrders.clear();
        jdbc.update("UPDATE courier_capacity SET capacity_limit = 3, committed_count = 0, reserved_count = 0, version = version + 1 WHERE courier_id = ?",
                bytes(DemoDataInitializer.DEMO_COURIER_ID));
        jdbc.update("UPDATE courier SET online_state = 'OFFLINE', region_id = NULL, latitude = NULL, longitude = NULL, last_position_at = NULL, heartbeat_sequence = 0, presence_generation = presence_generation + 1 WHERE id = ?",
                bytes(DemoDataInitializer.DEMO_COURIER_ID));
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @Order(1)
    void mysqlRowLockAndVersionCas_allowOnlyOneLastCapacityOffer() throws Exception {
        int used = jdbc.queryForObject("SELECT committed_count + reserved_count FROM courier_capacity WHERE courier_id = ?",
                Integer.class, bytes(DemoDataInitializer.DEMO_COURIER_ID));
        jdbc.update("UPDATE courier_capacity SET capacity_limit = ?, version = version + 1 WHERE courier_id = ?",
                used + 1, bytes(DemoDataInitializer.DEMO_COURIER_ID));
        bringCourierOnline();
        DeliveryOrder first = readyOrder();
        DeliveryOrder second = readyOrder();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            dispatch.advanceCreatedOrder(first.id());
            dispatch.advanceCreatedOrder(second.id());
            Future<DispatchService.DispatchDecision> a = race(pool, ready, start, first.id());
            Future<DispatchService.DispatchDecision> b = race(pool, ready, start, second.id());
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int offers = ("OFFER_CREATED".equals(a.get(10, TimeUnit.SECONDS).outcome()) ? 1 : 0)
                    + ("OFFER_CREATED".equals(b.get(10, TimeUnit.SECONDS).outcome()) ? 1 : 0);
            assertThat(offers).isEqualTo(1);
            assertCapacityWithinLimit();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_offer WHERE status = 'OFFERED' AND courier_id = ?",
                    Integer.class, bytes(DemoDataInitializer.DEMO_COURIER_ID))).isEqualTo(1);
            assertDatabaseInvariants();
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(2)
    void duplicateAccepts_createOneAssignmentAndOneCapacityCommit() throws Exception {
        DeliveryOrder order = readyOrder();
        DispatchService.DispatchDecision offer = dispatchOffer(order);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<DispatchRepository.OfferCommandResult> first = acceptRace(pool, ready, start, offer);
            Future<DispatchRepository.OfferCommandResult> second = acceptRace(pool, ready, start, offer);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<DispatchRepository.OfferCommandResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(DispatchRepository.OfferCommandResult::changed)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE offer_id = ? AND status = 'ACCEPTED'",
                    Integer.class, bytes(offer.offer().offerId()))).isEqualTo(1);
            assertDatabaseInvariants();
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(3)
    void acceptAndTimeoutAtExpiry_competeThroughOfferRowLock() throws Exception {
        DeliveryOrder order = readyOrder();
        DispatchService.DispatchDecision issued = dispatchOffer(order);
        Instant expiresAt = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("UPDATE dispatch_offer SET expires_at = ? WHERE id = ?", Timestamp.from(expiresAt),
                bytes(issued.offer().offerId()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> accept = pool.submit(() -> {
            ready.countDown();
            awaitUntil(start, expiresAt);
            try {
                return dispatch.accept(issued.offer().courierId(), issued.offer().offerId(), issued.offer().offerToken(),
                        issued.offer().orderVersion()).changed();
            } catch (DispatchOfferConflictException ignored) {
                return false;
            }
        });
        Future<Boolean> expire = pool.submit(() -> {
            ready.countDown();
            awaitUntil(start, expiresAt);
            return dispatchRepository.expireOffer(issued.offer().offerId(), Instant.now()).changed();
        });
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean accepted = accept.get(10, TimeUnit.SECONDS);
            boolean expired = expire.get(10, TimeUnit.SECONDS);
            assertThat(accepted).isFalse();
            assertThat(expired).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE offer_id = ? AND status = 'ACCEPTED'",
                    Integer.class, bytes(issued.offer().offerId()))).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class,
                    bytes(issued.offer().offerId()))).isEqualTo("EXPIRED");
            assertDatabaseInvariants();
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(4)
    void generationChange_racingAccept_neverAcceptsOldGenerationAfterFence() throws Exception {
        DeliveryOrder order = readyOrder();
        DispatchService.DispatchDecision issued = dispatchOffer(order);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> accept = pool.submit(() -> {
            await(start);
            try {
                return dispatch.accept(issued.offer().courierId(), issued.offer().offerId(), issued.offer().offerToken(), issued.offer().orderVersion()).changed();
            } catch (RuntimeException ignored) {
                return false;
            }
        });
        Future<?> reconnect = pool.submit(() -> {
            await(start);
            presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        });
        start.countDown();
        boolean accepted = accept.get(10, TimeUnit.SECONDS);
        reconnect.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class,
                bytes(issued.offer().offerId()))).isEqualTo(accepted ? "ACCEPTED" : "SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE offer_id = ? AND status = 'ACCEPTED'",
                Integer.class, bytes(issued.offer().offerId()))).isEqualTo(accepted ? 1 : 0);
        assertDatabaseInvariants();
    }

    @Test
    @Order(5)
    void twoWorkersForOneOrder_createAtMostOneOffer() throws Exception {
        DeliveryOrder order = readyOrder();
        dispatch.advanceCreatedOrder(order.id());
        bringCourierOnline();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<DispatchService.DispatchDecision> one = pool.submit(() -> dispatch.tryDispatch(order.id()));
        Future<DispatchService.DispatchDecision> two = pool.submit(() -> dispatch.tryDispatch(order.id()));
        one.get(10, TimeUnit.SECONDS);
        two.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_offer WHERE order_id = ? AND status = 'OFFERED'",
                Integer.class, bytes(order.id()))).isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE order_id = ? AND released_at IS NULL",
                Integer.class, bytes(order.id()))).isLessThanOrEqualTo(1);
        assertDatabaseInvariants();
    }

    @Test
    @Order(6)
    void redisLeaseRollback_releasesProvisionalLease_andCapacityRemainsDbAuthoritative() {
        var courier = bringCourierOnline();
        UUID offerId = UUID.randomUUID();
        CourierProjection.Lease lease = projection.reserve(courier, offerId, "rollback-token", 90, 10_000);
        assertThat(lease).isNotNull();
        try {
            transactions.execute(status -> {
                jdbc.update("UPDATE courier_capacity SET reserved_count = reserved_count + 1 WHERE courier_id = ?",
                        bytes(courier.courierId()));
                status.setRollbackOnly();
                return null;
            });
        } finally {
            projection.release(lease);
            projection.release(lease);
        }
        assertThat(redis.opsForHash().hasKey("pd:real-it:courier:{real-it:" + courier.courierId() + "}:leases", offerId.toString())).isFalse();
        assertThat(jdbc.queryForObject("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?", Integer.class,
                bytes(courier.courierId()))).isZero();
        assertDatabaseInvariants();
    }

    @Test
    @Order(7)
    void capacityKeyLossIsRebuiltFromMysqlAndFirstReserveFailsClosed() {
        var courier = bringCourierOnline();
        String capacityKey = "pd:real-it:courier:{real-it:" + courier.courierId() + "}:capacity";
        redis.delete(capacityKey);

        assertThat(projection.reserve(courier, UUID.randomUUID(), "lost-capacity", 90, 10_000)).isNull();
        assertThat(redis.opsForHash().get(capacityKey, "ready")).isEqualTo("1");
        CourierProjection.Lease repairedLease = projection.reserve(courier, UUID.randomUUID(), "after-rebuild", 90, 10_000);
        assertThat(repairedLease).isNotNull();
        projection.release(repairedLease);
        projection.release(repairedLease);
        assertDatabaseInvariants();
    }

    @Test
    @Order(8)
    void databaseCommitSurvivesRedisNotificationFailureAndOutboxReplaysAfterRedisReturns() {
        DeliveryOrder order = readyOrder();
        bringCourierOnline();
        dispatch.advanceCreatedOrder(order.id());
        REDIS.stop();
        try {
            var updated = presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
            var unavailable = dispatch.tryDispatch(order.id());
            assertThat(unavailable.outcome()).isEqualTo("REDIS_UNAVAILABLE");
            assertThat(dispatchReadiness.ready()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_offer WHERE order_id = ? AND status = 'OFFERED'",
                    Integer.class, bytes(order.id()))).isZero();
            assertThat(couriers.find(DemoDataInitializer.DEMO_COURIER_ID).orElseThrow().presenceGeneration())
                    .isEqualTo(updated.presenceGeneration());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic = 'courier.presence_changed' AND aggregate_id = ? AND published_at IS NULL",
                    Integer.class, bytes(DemoDataInitializer.DEMO_COURIER_ID))).isGreaterThan(0);
        } finally {
            REDIS.start();
        }
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager,
                Clock.systemUTC(), "real-it", 10, 30);
        RedisStreamConsumer consumer = new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection,
                new ObjectMapper(), Clock.systemUTC(), "real-it", 5);
        publisher.publishBatch();
        consumer.consumeBatch();
        assertThat(dispatchReadiness.ready()).isFalse();
        new RedisProjectionRecoveryWorker(couriers, projection, redisAvailability, dispatchReadiness)
                .reconcileOnlineCouriers();
        assertThat(dispatchReadiness.ready()).isTrue();
        assertThat(redis.opsForHash().get("pd:real-it:courier:{real-it:"
                + DemoDataInitializer.DEMO_COURIER_ID + "}:presence", "online")).isEqualTo("1");
        var recovered = couriers.find(DemoDataInitializer.DEMO_COURIER_ID).orElseThrow();
        presence.heartbeat(recovered.courierId(), recovered.regionId(), recovered.presenceGeneration(),
                recovered.sequence() + 1, 30.250, 120.160);
        jdbc.update("UPDATE delivery_order SET next_dispatch_at = CURRENT_TIMESTAMP(3) WHERE id = ?", bytes(order.id()));
        assertThat(dispatch.tryDispatch(order.id()).outcome()).isEqualTo("OFFER_CREATED");
        assertDatabaseInvariants();
    }

    @Test
    @Order(9)
    void duplicateStreamDeliveryHasOneDatabaseEffectAndOneInboxCompletion() {
        DeliveryOrder order = readyOrder();
        UUID eventId = UUID.randomUUID();
        Map<String, String> event = new HashMap<>();
        event.put("eventId", eventId.toString());
        event.put("eventType", "order.created");
        event.put("schemaVersion", "1");
        event.put("aggregateType", "ORDER");
        event.put("aggregateId", order.id().toString());
        event.put("aggregateVersion", Long.toString(order.version()));
        event.put("dedupeKey", "order:" + order.id() + ":created");
        event.put("payload", "{}");
        String stream = "pd:real-it:stream:dispatch";
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(stream, event));
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(stream, event));
        RedisStreamConsumer consumer = new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection,
                new ObjectMapper(), Clock.systemUTC(), "real-it", 5);

        consumer.consumeBatch();

        assertThat(orders.get(order.id()).version()).isEqualTo(order.version() + 1);
        assertThat(orders.get(order.id()).status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH);
        assertThat(jdbc.queryForObject("SELECT attempts FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                Integer.class, "peakdispatch:peakdispatch-dispatch-v1", bytes(eventId))).isEqualTo(1);
        assertThat(redis.opsForStream().pending(stream, "peakdispatch-dispatch-v1").getTotalPendingMessages()).isZero();
        assertDatabaseInvariants();
    }

    @Test
    @Order(10)
    void newCourierGenerationRejectsOldOfferAndCannotRestoreOldGeoIndex() {
        DeliveryOrder order = readyOrder();
        DispatchService.DispatchDecision issued = dispatchOffer(order);
        var oldGeneration = couriers.find(DemoDataInitializer.DEMO_COURIER_ID).orElseThrow();

        presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);

        assertThat(dispatch.accept(issued.offer().courierId(), issued.offer().offerId(),
                issued.offer().offerToken(), issued.offer().orderVersion()).changed()).isFalse();
        assertThatThrownBy(() -> projection.projectSession(oldGeneration))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class,
                bytes(issued.offer().offerId()))).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE order_id = ? AND released_at IS NULL",
                Integer.class, bytes(order.id()))).isZero();
        assertThat(projection.nearby(DemoDataInitializer.DEMO_REGION_ID, 30.250, 120.160, 1, 90)).isEmpty();
        assertDatabaseInvariants();
    }

    @Test
    @Order(11)
    void dbCommitBeforeAckFailureIsRecoveredByInboxWithoutRepeatingOrderEffect() {
        DeliveryOrder order = readyOrder();
        UUID eventId = UUID.randomUUID();
        Map<String, String> event = new HashMap<>();
        event.put("eventId", eventId.toString());
        event.put("eventType", "order.created");
        event.put("schemaVersion", "1");
        event.put("aggregateType", "ORDER");
        event.put("aggregateId", order.id().toString());
        event.put("aggregateVersion", Long.toString(order.version()));
        event.put("dedupeKey", "order:" + order.id() + ":created");
        event.put("payload", "{}");
        String stream = "pd:real-it:stream:dispatch";
        var recordId = redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(stream, event));
        RedisStreamConsumer interruptedAfterCommit = new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection,
                new ObjectMapper(), Clock.systemUTC(), "real-it", 5) {
            @Override
            protected void ack(String source, String group, org.springframework.data.redis.connection.stream.RecordId id) {
                throw new IllegalStateException("simulated worker termination before XACK");
            }
        };

        interruptedAfterCommit.consumeBatch();

        long committedVersion = orders.get(order.id()).version();
        assertThat(committedVersion).isEqualTo(order.version() + 1);
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                String.class, "peakdispatch:peakdispatch-dispatch-v1", bytes(eventId))).isEqualTo("PROCESSED");
        assertThat(redis.opsForStream().pending(stream, "peakdispatch-dispatch-v1").getTotalPendingMessages()).isEqualTo(1);
        forcePendingIdle(stream, "peakdispatch-dispatch-v1", recordId.getValue());

        new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection, new ObjectMapper(),
                Clock.systemUTC(), "real-it", 5).consumeBatch();

        assertThat(orders.get(order.id()).version()).isEqualTo(committedVersion);
        assertThat(jdbc.queryForObject("SELECT attempts FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                Integer.class, "peakdispatch:peakdispatch-dispatch-v1", bytes(eventId))).isEqualTo(1);
        assertThat(redis.opsForStream().pending(stream, "peakdispatch-dispatch-v1").getTotalPendingMessages()).isZero();
        assertDatabaseInvariants();
    }

    @Test
    @Order(12)
    void mysqlInboxAndRedisDlqReplayAreIdempotentAfterHandlerFailure() {
        bringCourierOnline();
        UUID eventId = UUID.randomUUID();
        Map<String, String> event = new HashMap<>();
        event.put("eventId", eventId.toString());
        event.put("eventType", "courier.capacity_changed");
        event.put("schemaVersion", "1");
        event.put("aggregateType", "COURIER");
        event.put("aggregateId", DemoDataInitializer.DEMO_COURIER_ID.toString());
        event.put("aggregateVersion", "1");
        event.put("dedupeKey", "courier:" + DemoDataInitializer.DEMO_COURIER_ID + ":capacity:test");
        event.put("payload", "{invalid-json");
        String sourceStream = "pd:real-it:stream:events";
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(sourceStream, event));
        RedisStreamConsumer consumer = new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection,
                new ObjectMapper(), Clock.systemUTC(), "real-it", 1);

        consumer.consumeBatch();

        String consumerName = "peakdispatch:peakdispatch-events-v1";
        String dlq = com.peakdispatch.adapter.redis.RedisKeyspace.consumerDeadLetterKeys("real-it").getFirst();
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                String.class, consumerName, bytes(eventId))).isEqualTo("DEAD_LETTER");
        assertThat(jdbc.queryForObject("SELECT dlq_stream_id FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                String.class, consumerName, bytes(eventId))).isNotBlank();
        assertThat(redis.opsForStream().size(dlq)).isEqualTo(1);
        assertThat(redis.opsForStream().pending(sourceStream, "peakdispatch-events-v1").getTotalPendingMessages()).isZero();

        String replayGroup = "real-infra-dlq-replay";
        redis.opsForStream().createGroup(dlq,
                org.springframework.data.redis.connection.stream.ReadOffset.from("0-0"), replayGroup);
        var deadLetters = redis.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(replayGroup, "replayer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(1),
                org.springframework.data.redis.connection.stream.StreamOffset.create(dlq,
                        org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
        Map<Object, Object> corrected = new HashMap<>(deadLetters.getFirst().getValue());
        corrected.put("payload", "{\"offerId\":null}");
        var replayRecord = org.springframework.data.redis.connection.stream.MapRecord.create(dlq, corrected)
                .withId(deadLetters.getFirst().getId());
        RedisDeadLetterReplayer replayer = new RedisDeadLetterReplayer(redis, inbox, Clock.systemUTC());
        assertThat(replayer.replay(replayRecord, consumerName, replayGroup)).isTrue();
        assertThat(replayer.replay(replayRecord, consumerName, replayGroup)).isFalse();

        new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection, new ObjectMapper(),
                Clock.systemUTC(), "real-it", 5).consumeBatch();

        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                String.class, consumerName, bytes(eventId))).isEqualTo("PROCESSED");
        assertThat(redis.opsForStream().pending(sourceStream, "peakdispatch-events-v1").getTotalPendingMessages()).isZero();
        assertThat(redis.opsForStream().size(dlq)).isEqualTo(1);
        assertDatabaseInvariants();
    }

    @Test
    @Order(13)
    void failedDlqWriteLeavesMysqlInboxAndStreamPendingUntilDlqRecovers() {
        bringCourierOnline();
        UUID eventId = UUID.randomUUID();
        Map<String, String> event = new HashMap<>();
        event.put("eventId", eventId.toString());
        event.put("eventType", "courier.capacity_changed");
        event.put("schemaVersion", "1");
        event.put("aggregateType", "COURIER");
        event.put("aggregateId", DemoDataInitializer.DEMO_COURIER_ID.toString());
        event.put("aggregateVersion", "1");
        event.put("dedupeKey", "courier:" + DemoDataInitializer.DEMO_COURIER_ID + ":capacity:dlq-failure");
        event.put("payload", "{invalid-json");
        String sourceStream = "pd:real-it:stream:events";
        var recordId = redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(sourceStream, event));
        List<String> dlqKeys = com.peakdispatch.adapter.redis.RedisKeyspace.consumerDeadLetterKeys("real-it");
        redis.opsForValue().set(dlqKeys.get(1), "wrong-key-type");

        new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection, new ObjectMapper(),
                Clock.systemUTC(), "real-it", 1).consumeBatch();

        String group = "peakdispatch-events-v1";
        String consumerName = "peakdispatch:" + group;
        assertThat(redis.opsForStream().pending(sourceStream, group).getTotalPendingMessages()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                String.class, consumerName, bytes(eventId))).isEqualTo("PROCESSING");
        assertThat(redis.hasKey(dlqKeys.getFirst())).isFalse();
        redis.delete(dlqKeys.get(1));
        forcePendingIdle(sourceStream, group, recordId.getValue());

        new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection, new ObjectMapper(),
                Clock.systemUTC(), "real-it", 1).consumeBatch();

        assertThat(redis.opsForStream().pending(sourceStream, group).getTotalPendingMessages()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                String.class, consumerName, bytes(eventId))).isEqualTo("DEAD_LETTER");
        assertThat(redis.opsForStream().size(dlqKeys.getFirst())).isEqualTo(1);
        assertDatabaseInvariants();
    }

    @Test
    @Order(14)
    void outboxXaddBeforePublishedMarkCanDuplicateStreamEntryButInboxEffectIsOnce() {
        presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        Map<String, String> envelope = jdbc.queryForObject("""
                SELECT event_id, topic, aggregate_type, aggregate_id, aggregate_version, dedupe_key, payload_json
                FROM outbox_event WHERE aggregate_id = ? AND topic = 'courier.presence_changed'
                  AND published_at IS NULL ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> Map.of(
                "eventId", uuid(rs.getBytes("event_id")).toString(),
                "eventType", rs.getString("topic"),
                "schemaVersion", "1",
                "aggregateType", rs.getString("aggregate_type"),
                "aggregateId", uuid(rs.getBytes("aggregate_id")).toString(),
                "aggregateVersion", Long.toString(rs.getLong("aggregate_version")),
                "dedupeKey", rs.getString("dedupe_key"),
                "payload", rs.getString("payload_json")), bytes(DemoDataInitializer.DEMO_COURIER_ID));
        UUID eventId = UUID.fromString(envelope.get("eventId"));
        String stream = "pd:real-it:stream:events";
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(stream, envelope));
        OutboxPublisher publisher = new OutboxPublisher(jdbc, redis, transactionManager,
                Clock.systemUTC(), "real-it", 10, 30);

        publisher.publishBatch();

        List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> entries =
                redis.opsForStream().range(stream, org.springframework.data.domain.Range.unbounded());
        assertThat(entries.stream().filter(record -> eventId.toString().equals(record.getValue().get("eventId"))).count())
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_event WHERE event_id = ?",
                Boolean.class, bytes(eventId))).isTrue();
        new RedisStreamConsumer(redis, inbox, dispatch, couriers, projection, new ObjectMapper(),
                Clock.systemUTC(), "real-it", 5).consumeBatch();
        assertThat(jdbc.queryForObject("SELECT attempts FROM inbox_message WHERE consumer_name = ? AND event_id = ?",
                Integer.class, "peakdispatch:peakdispatch-events-v1", bytes(eventId))).isEqualTo(1);
        assertDatabaseInvariants();
    }

    private DeliveryOrder readyOrder() {
        Instant now = Instant.now();
        DeliveryOrder order = orders.create(new OrderApplicationService.CreateOrderCommand(
                DemoDataInitializer.DEMO_MERCHANT_ID, DemoDataInitializer.DEMO_REGION_ID,
                30.250, 120.160, 30.251, 120.161, now.plusSeconds(300), now.plusSeconds(3600)));
        testOrders.add(order.id());
        return order;
    }

    private DispatchService.DispatchDecision dispatchOffer(DeliveryOrder order) {
        bringCourierOnline();
        dispatch.advanceCreatedOrder(order.id());
        DispatchService.DispatchDecision result = dispatch.tryDispatch(order.id());
        assertThat(result.outcome()).isEqualTo("OFFER_CREATED");
        return result;
    }

    private com.peakdispatch.domain.courier.CourierPresence bringCourierOnline() {
        var online = presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        return presence.heartbeat(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID,
                online.presenceGeneration(), 1, 30.250, 120.160);
    }

    private Future<DispatchService.DispatchDecision> race(ExecutorService pool, CountDownLatch ready,
                                                            CountDownLatch start, UUID orderId) {
        return pool.submit(() -> {
            ready.countDown();
            await(start);
            return dispatch.tryDispatch(orderId);
        });
    }

    private Future<DispatchRepository.OfferCommandResult> acceptRace(ExecutorService pool, CountDownLatch ready,
                                                                       CountDownLatch start,
                                                                       DispatchService.DispatchDecision offer) {
        return pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                return dispatch.accept(offer.offer().courierId(), offer.offer().offerId(), offer.offer().offerToken(),
                        offer.offer().orderVersion());
            } catch (DispatchOfferConflictException conflict) {
                return new DispatchRepository.OfferCommandResult(null, null, false);
            }
        });
    }

    private void assertCapacityWithinLimit() {
        Integer violations = jdbc.queryForObject("SELECT COUNT(*) FROM courier_capacity WHERE committed_count + reserved_count > capacity_limit", Integer.class);
        assertThat(violations).isZero();
    }

    private void assertDatabaseInvariants() {
        assertCapacityWithinLimit();
        Integer ledgerMismatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM courier_capacity c
                WHERE c.committed_count <> (
                    SELECT COUNT(*) FROM dispatch_assignment a
                    WHERE a.courier_id = c.courier_id AND a.released_at IS NULL
                ) OR c.reserved_count <> (
                    SELECT COUNT(*) FROM dispatch_offer o
                    WHERE o.courier_id = c.courier_id AND o.status = 'OFFERED'
                )
                """, Integer.class);
        assertThat(ledgerMismatches).isZero();
        Integer duplicateAssignments = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT order_id FROM dispatch_assignment WHERE released_at IS NULL
                    GROUP BY order_id HAVING COUNT(*) > 1
                ) active_assignments
                """, Integer.class);
        assertThat(duplicateAssignments).isZero();
        Integer duplicateOffers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT order_id FROM dispatch_offer WHERE status = 'OFFERED'
                    GROUP BY order_id HAVING COUNT(*) > 1
                ) active_offers
                """, Integer.class);
        assertThat(duplicateOffers).isZero();
    }

    private void forcePendingIdle(String stream, String group, String recordId) {
        redis.execute((RedisCallback<Object>) connection -> connection.execute("XCLAIM",
                bytes(stream), bytes(group), bytes("restart-simulator"), bytes("0"), bytes(recordId),
                bytes("IDLE"), bytes("6000")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency test timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void awaitUntil(CountDownLatch start, Instant instant) {
        await(start);
        try {
            long remainingNanos;
            while ((remainingNanos = java.time.Duration.between(Instant.now(), instant).toNanos()) > 0) {
                TimeUnit.NANOSECONDS.sleep(remainingNanos);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
