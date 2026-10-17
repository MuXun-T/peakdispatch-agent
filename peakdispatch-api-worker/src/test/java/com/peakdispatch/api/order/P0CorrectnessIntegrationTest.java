package com.peakdispatch.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peakdispatch.application.courier.CourierPresenceService;
import com.peakdispatch.application.dispatch.DispatchOfferConflictException;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.config.DemoDataInitializer;
import com.peakdispatch.domain.dispatch.DispatchOfferStatus;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.InvalidOrderTransitionException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P0CorrectnessIntegrationTest {
    @Autowired OrderApplicationService orders;
    @Autowired CourierPresenceService presence;
    @Autowired DispatchRepository dispatchRepository;
    @Autowired DispatchService dispatch;
    @Autowired JdbcTemplate jdbc;

    @Test
    void cancelOfferedOrder_releasesReservationClosesSlotAndIsIdempotent() {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        long offeredVersion = orders.get(created.id()).version();

        DeliveryOrder cancelled = orders.cancel(created.id(), offeredVersion, "merchant_requested", "merchant-test");

        assertThat(cancelled.status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.CANCELLED);
        assertThat(count("SELECT COUNT(*) FROM dispatch_offer WHERE order_id = ? AND status = 'SUPERSEDED'",
                created.id())).isEqualTo(1);
        assertThat(count("SELECT reserved_count + committed_count FROM courier_capacity WHERE courier_id = ?",
                DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT phase FROM order_dispatch_slot WHERE order_id = ?", String.class,
                bytes(created.id()))).isEqualTo("CLOSED");
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.cancelled'",
                created.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND action = 'ORDER_CANCELLED'",
                created.id().toString())).isEqualTo(1);

        DeliveryOrder replay = orders.cancel(created.id(), offeredVersion, "merchant_requested", "merchant-test");
        assertThat(replay.version()).isEqualTo(cancelled.version());
        assertThat(replay.status()).isEqualTo(cancelled.status());
        assertThat(decision.offer()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.cancelled'",
                created.id())).isEqualTo(1);
    }

    @Test
    void rescheduleAcceptedOrder_releasesCommittedCapacityAndRequeues() {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        DispatchRepositoryResult accepted = accept(decision);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant prepEta = base.plusSeconds(600);
        Instant deadline = base.plusSeconds(4200);

        DeliveryOrder rescheduled = orders.approveReschedule(created.id(), accepted.order().version(), prepEta,
                deadline, "merchant_delay", "approver-test");

        assertThat(rescheduled.status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH);
        assertThat(rescheduled.prepEta()).isEqualTo(prepEta);
        assertThat(rescheduled.deadline()).isEqualTo(deadline);
        assertThat(count("SELECT reserved_count + committed_count FROM courier_capacity WHERE courier_id = ?",
                DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_assignment WHERE order_id = ?", String.class,
                bytes(created.id()))).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT phase FROM order_dispatch_slot WHERE order_id = ?", String.class,
                bytes(created.id()))).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?", Integer.class,
                bytes(created.id()))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE dedupe_key LIKE ?",
                "dispatch:" + created.id() + ":rescheduled:%")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.rescheduled'",
                created.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND action = 'ORDER_RESCHEDULED'",
                created.id().toString())).isEqualTo(1);

        DeliveryOrder replay = orders.approveReschedule(created.id(), accepted.order().version(), prepEta, deadline,
                "merchant_delay", "approver-test");
        assertThat(replay.version()).isEqualTo(rescheduled.version());
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.rescheduled'",
                created.id())).isEqualTo(1);
    }

    @Test
    void preparationChange_invalidatesUnfeasibleOfferAndRequeues() {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        DeliveryOrder offered = orders.get(created.id());

        DeliveryOrder updated = orders.updatePreparation(DemoDataInitializer.DEMO_MERCHANT_ID, created.id(),
                offered.version(), offered.deadline().minusSeconds(1), false, UUID.randomUUID(), "merchant-test");

        assertThat(updated.status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH);
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class,
                bytes(decision.offer().offerId()))).isEqualTo(DispatchOfferStatus.SUPERSEDED.name());
        assertThat(jdbc.queryForObject("SELECT invalidation_reason FROM dispatch_offer WHERE id = ?", String.class,
                bytes(decision.offer().offerId()))).isEqualTo("PREPARATION_INVALIDATED");
        assertThat(count("SELECT reserved_count + committed_count FROM courier_capacity WHERE courier_id = ?",
                DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?", Integer.class,
                bytes(created.id()))).isEqualTo(2);
    }

    @Test
    void preparationChangeWhileWaiting_advancesRoundAndUsesPolicyBackoff() {
        DeliveryOrder created = createOrder();
        dispatch.advanceCreatedOrder(created.id());
        DeliveryOrder waiting = orders.get(created.id());
        Instant before = Instant.now();

        DeliveryOrder updated = orders.updatePreparation(DemoDataInitializer.DEMO_MERCHANT_ID, created.id(),
                waiting.version(), waiting.prepEta().plusSeconds(60), true, UUID.randomUUID(), "merchant-test");

        assertThat(updated.status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH);
        assertThat(jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?", Integer.class,
                bytes(created.id()))).isEqualTo(2);
        Instant nextDispatchAt = jdbc.queryForObject("SELECT next_dispatch_at FROM delivery_order WHERE id = ?",
                Timestamp.class, bytes(created.id())).toInstant();
        assertThat(nextDispatchAt).isAfter(before.plusSeconds(3));
        assertThat(nextDispatchAt).isBefore(Instant.now().plusSeconds(8));
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE dedupe_key LIKE ?",
                "dispatch:" + created.id() + ":prep:%")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.dispatch_requested'",
                created.id())).isEqualTo(2);
    }

    @Test
    void initialDispatchTime_isFlooredToDatabaseTimestampPrecision() {
        DeliveryOrder created = createOrder();
        Instant requestedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .plusSeconds(5).plusNanos(999_600_000);

        assertThat(dispatchRepository.markWaitingDispatch(created.id(), created.version(), requestedAt)).isTrue();

        Instant persistedAt = jdbc.queryForObject("SELECT next_dispatch_at FROM delivery_order WHERE id = ?",
                Timestamp.class, bytes(created.id())).toInstant();
        assertThat(persistedAt).isEqualTo(requestedAt.truncatedTo(ChronoUnit.MILLIS));
        assertThat(persistedAt).isBefore(requestedAt);
    }

    @Test
    void rejectedOffers_afterFiveRounds_enterOperationsEscalation() {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);

        for (int round = 1; round <= 5; round++) {
            if (round > 1) {
                jdbc.update("UPDATE delivery_order SET next_dispatch_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now().minusSeconds(1)), bytes(created.id()));
                decision = dispatch.tryDispatch(created.id());
            }
            assertThat(decision.outcome()).isEqualTo("OFFER_CREATED");
            DispatchRepositoryResult rejected = new DispatchRepositoryResult(dispatch.reject(
                    DemoDataInitializer.DEMO_COURIER_ID, decision.offer().offerId(),
                    decision.offer().offerToken(), "TEST_REJECT").order());
            if (round < 5) {
                assertThat(rejected.order().status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH);
            } else {
                assertThat(rejected.order().status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.OPS_ESCALATED);
            }
        }

        assertThat(jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?", Integer.class,
                bytes(created.id()))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT next_dispatch_at FROM delivery_order WHERE id = ?", Timestamp.class,
                bytes(created.id()))).isNull();
        assertThat(count("SELECT COUNT(*) FROM dispatch_attempt WHERE order_id = ?", created.id())).isEqualTo(5);
    }

    @Test
    void acceptAndTimeoutRace_resolvesOfferAndCapacityExactlyOnce() throws Exception {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        UUID offerId = decision.offer().offerId();
        UUID courierId = decision.offer().courierId();
        int committedBefore = count("SELECT committed_count FROM courier_capacity WHERE courier_id = ?", courierId);
        int reservedBefore = count("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?", courierId);
        Instant expiresAt = Instant.now().plusMillis(250);
        jdbc.update("UPDATE dispatch_offer SET expires_at = ? WHERE id = ?", Timestamp.from(expiresAt), bytes(offerId));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        boolean accepted;
        try {
            var timeout = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("offer race timed out");
                while (Instant.now().isBefore(expiresAt)) TimeUnit.MILLISECONDS.sleep(1);
                dispatch.expireOffers();
                return null;
            });
            var accept = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("offer race timed out");
                try {
                    return dispatch.accept(courierId, offerId, decision.offer().offerToken(),
                            decision.offer().orderVersion()).changed();
                } catch (DispatchOfferConflictException expired) {
                    return false;
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            accepted = accept.get(5, TimeUnit.SECONDS);
            timeout.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        String expectedOfferStatus = accepted ? DispatchOfferStatus.ACCEPTED.name()
                : DispatchOfferStatus.EXPIRED.name();
        var expectedOrderStatus = accepted ? com.peakdispatch.domain.order.OrderStatus.ACCEPTED
                : com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH;
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class,
                bytes(offerId))).isEqualTo(expectedOfferStatus);
        assertThat(orders.get(created.id()).status()).isEqualTo(expectedOrderStatus);
        assertThat(count("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?", courierId))
                .isEqualTo(reservedBefore - 1);
        assertThat(count("SELECT committed_count FROM courier_capacity WHERE courier_id = ?", courierId))
                .isEqualTo(committedBefore + (accepted ? 1 : 0));
        assertThat(count("SELECT COUNT(*) FROM dispatch_assignment WHERE offer_id = ?", offerId))
                .isEqualTo(accepted ? 1 : 0);
        DeliveryOrder resolved = orders.get(created.id());
        orders.cancel(resolved.id(), resolved.version(), "test_cleanup", "merchant-test");
    }

    @Test
    void competingOrdersForLastCapacitySlot_createAtMostOneOffer() throws Exception {
        int originalLimit = count("SELECT capacity_limit FROM courier_capacity WHERE courier_id = ?",
                DemoDataInitializer.DEMO_COURIER_ID);
        jdbc.update("""
                UPDATE courier_capacity
                SET capacity_limit = committed_count + reserved_count + 1, version = version + 1
                WHERE courier_id = ?
                """, bytes(DemoDataInitializer.DEMO_COURIER_ID));
        int usedCapacity = count("SELECT committed_count + reserved_count FROM courier_capacity WHERE courier_id = ?",
                DemoDataInitializer.DEMO_COURIER_ID);

        DeliveryOrder firstOrder = createOrder();
        DeliveryOrder secondOrder = createOrder();
        var online = presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        presence.heartbeat(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID,
                online.presenceGeneration(), 1, 30.250, 120.160);
        dispatch.advanceCreatedOrder(firstOrder.id());
        dispatch.advanceCreatedOrder(secondOrder.id());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        UUID offeredOrderId = null;
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("dispatch race timed out");
                return dispatch.tryDispatch(firstOrder.id());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("dispatch race timed out");
                return dispatch.tryDispatch(secondOrder.id());
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstDecision = first.get(5, TimeUnit.SECONDS);
            var secondDecision = second.get(5, TimeUnit.SECONDS);
            boolean firstWon = "OFFER_CREATED".equals(firstDecision.outcome());
            boolean secondWon = "OFFER_CREATED".equals(secondDecision.outcome());
            offeredOrderId = firstWon ? firstOrder.id() : secondWon ? secondOrder.id() : null;

            assertThat((firstWon ? 1 : 0) + (secondWon ? 1 : 0)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM dispatch_offer WHERE status = 'OFFERED' AND courier_id = ?",
                    DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(1);
            assertThat(count("SELECT committed_count + reserved_count FROM courier_capacity WHERE courier_id = ?",
                    DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(usedCapacity + 1);
            assertThat(count("SELECT capacity_limit - committed_count - reserved_count FROM courier_capacity WHERE courier_id = ?",
                    DemoDataInitializer.DEMO_COURIER_ID)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            if (offeredOrderId != null) {
                DeliveryOrder offered = orders.get(offeredOrderId);
                orders.cancel(offered.id(), offered.version(), "test_cleanup", "merchant-test");
            }
            jdbc.update("UPDATE courier_capacity SET capacity_limit = ?, version = version + 1 WHERE courier_id = ?",
                    originalLimit, bytes(DemoDataInitializer.DEMO_COURIER_ID));
        }
    }

    @Test
    void concurrentDuplicateAccepts_commitCapacityAndAssignmentOnce() throws Exception {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        UUID offerId = decision.offer().offerId();
        UUID courierId = decision.offer().courierId();
        int committedBefore = count("SELECT committed_count FROM courier_capacity WHERE courier_id = ?", courierId);
        int reservedBefore = count("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?", courierId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("accept race timed out");
                return dispatch.accept(courierId, offerId, decision.offer().offerToken(),
                        decision.offer().orderVersion());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("accept race timed out");
                return dispatch.accept(courierId, offerId, decision.offer().offerToken(),
                        decision.offer().orderVersion());
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = java.util.List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(results.stream()
                    .filter(com.peakdispatch.application.dispatch.DispatchRepository.OfferCommandResult::changed))
                    .hasSize(1);
            assertThat(orders.get(created.id()).status())
                    .isEqualTo(com.peakdispatch.domain.order.OrderStatus.ACCEPTED);
            assertThat(count("SELECT COUNT(*) FROM dispatch_assignment WHERE offer_id = ? AND status = 'ACCEPTED'",
                    offerId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'offer.accepted'",
                    created.id())).isEqualTo(1);
            assertThat(count("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?", courierId))
                    .isEqualTo(reservedBefore - 1);
            assertThat(count("SELECT committed_count FROM courier_capacity WHERE courier_id = ?", courierId))
                    .isEqualTo(committedBefore + 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            DeliveryOrder current = orders.get(created.id());
            if (current.status() != com.peakdispatch.domain.order.OrderStatus.CANCELLED
                    && current.status() != com.peakdispatch.domain.order.OrderStatus.DELIVERED) {
                orders.cancel(current.id(), current.version(), "test_cleanup", "merchant-test");
            }
        }
    }

    @Test
    void reschedulePickedUpOrder_isRejected() {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        DispatchRepositoryResult accepted = accept(decision);
        dispatch.pickup(DemoDataInitializer.DEMO_COURIER_ID, created.id(), accepted.order().version(), UUID.randomUUID());

        assertThatThrownBy(() -> orders.approveReschedule(created.id(), accepted.order().version() + 1,
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(4200), "too_late", "approver-test"))
                .isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    void stalePresenceGeneration_invalidatesOfferAndRejectsLateAccept() {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        UUID offerId = decision.offer().offerId();

        jdbc.update("UPDATE courier SET presence_generation = presence_generation + 1 WHERE id = ?",
                bytes(DemoDataInitializer.DEMO_COURIER_ID));

        assertThatThrownBy(() -> dispatch.accept(DemoDataInitializer.DEMO_COURIER_ID, offerId,
                decision.offer().offerToken(), decision.offer().orderVersion()))
                .isInstanceOf(DispatchOfferConflictException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class, bytes(offerId)))
                .isEqualTo(DispatchOfferStatus.SUPERSEDED.name());
        assertThat(orders.get(created.id()).status()).isEqualTo(com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH);
        assertThat(count("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?",
                DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(0);
    }

    @Test
    void presenceGenerationChange_racingAcceptResolvesCapacityOnce() throws Exception {
        DeliveryOrder created = createOrder();
        DispatchService.DispatchDecision decision = createOffer(created);
        UUID offerId = decision.offer().offerId();
        UUID courierId = decision.offer().courierId();
        long generationBefore = jdbc.queryForObject("SELECT presence_generation FROM courier WHERE id = ?",
                Long.class, bytes(courierId));
        int committedBefore = count("SELECT committed_count FROM courier_capacity WHERE courier_id = ?", courierId);
        int reservedBefore = count("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?", courierId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        boolean accepted;
        try {
            var accept = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("generation race timed out");
                try {
                    return dispatch.accept(courierId, offerId, decision.offer().offerToken(),
                            decision.offer().orderVersion()).changed();
                } catch (DispatchOfferConflictException staleOffer) {
                    return false;
                }
            });
            var generationChange = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("generation race timed out");
                return presence.setOnline(courierId, DemoDataInitializer.DEMO_REGION_ID);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            accepted = accept.get(5, TimeUnit.SECONDS);
            generationChange.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("SELECT presence_generation FROM courier WHERE id = ?",
                Long.class, bytes(courierId)))
                .isGreaterThan(generationBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class,
                bytes(offerId))).isEqualTo(accepted ? DispatchOfferStatus.ACCEPTED.name()
                : DispatchOfferStatus.SUPERSEDED.name());
        assertThat(orders.get(created.id()).status()).isEqualTo(accepted
                ? com.peakdispatch.domain.order.OrderStatus.ACCEPTED
                : com.peakdispatch.domain.order.OrderStatus.WAITING_DISPATCH);
        assertThat(count("SELECT reserved_count FROM courier_capacity WHERE courier_id = ?", courierId))
                .isEqualTo(reservedBefore - 1);
        assertThat(count("SELECT committed_count FROM courier_capacity WHERE courier_id = ?", courierId))
                .isEqualTo(committedBefore + (accepted ? 1 : 0));
        assertThat(count("SELECT COUNT(*) FROM dispatch_assignment WHERE offer_id = ?", offerId))
                .isEqualTo(accepted ? 1 : 0);
        if (accepted) {
            DeliveryOrder acceptedOrder = orders.get(created.id());
            orders.cancel(acceptedOrder.id(), acceptedOrder.version(), "test_cleanup", "merchant-test");
        }
    }

    private DeliveryOrder createOrder() {
        Instant now = Instant.now();
        return orders.create(new OrderApplicationService.CreateOrderCommand(
                DemoDataInitializer.DEMO_MERCHANT_ID, DemoDataInitializer.DEMO_REGION_ID,
                30.250, 120.160, 30.251, 120.161,
                now.plusSeconds(300), now.plusSeconds(3600)));
    }

    private DispatchService.DispatchDecision createOffer(DeliveryOrder created) {
        var online = presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        presence.heartbeat(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID,
                online.presenceGeneration(), 1, 30.250, 120.160);
        dispatch.advanceCreatedOrder(created.id());
        DispatchService.DispatchDecision decision = dispatch.tryDispatch(created.id());
        assertThat(decision.outcome()).isEqualTo("OFFER_CREATED");
        return decision;
    }

    private DispatchRepositoryResult accept(DispatchService.DispatchDecision decision) {
        var accepted = dispatch.accept(DemoDataInitializer.DEMO_COURIER_ID, decision.offer().offerId(),
                decision.offer().offerToken(), decision.offer().orderVersion());
        assertThat(accepted.changed()).isTrue();
        return new DispatchRepositoryResult(accepted.order());
    }

    private int count(String sql, Object parameter) {
        Integer value = jdbc.queryForObject(sql, Integer.class,
                parameter instanceof UUID uuid ? bytes(uuid) : parameter);
        return value == null ? 0 : value;
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private record DispatchRepositoryResult(DeliveryOrder order) {
    }
}
