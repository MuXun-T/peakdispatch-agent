package com.peakdispatch.adapter.mysql;

import com.peakdispatch.application.dispatch.DispatchOfferNotFoundException;
import com.peakdispatch.application.dispatch.DispatchOfferConflictException;
import com.peakdispatch.application.dispatch.DispatchPolicyProvider;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.application.order.OrderVersionConflictException;
import com.peakdispatch.domain.courier.CourierPresence;
import com.peakdispatch.domain.dispatch.DispatchCandidate;
import com.peakdispatch.domain.dispatch.DispatchOffer;
import com.peakdispatch.domain.dispatch.DispatchOfferStatus;
import com.peakdispatch.domain.dispatch.DispatchPolicy;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDispatchRepository implements DispatchRepository {
    private final JdbcTemplate jdbc;
    private final DispatchPolicyProvider policies;

    public JdbcDispatchRepository(JdbcTemplate jdbc, DispatchPolicyProvider policies) {
        this.jdbc = jdbc;
        this.policies = policies;
    }

    @Override
    public List<DeliveryOrder> findCreated(int limit) {
        return jdbc.query(orderSelect() + " WHERE status = 'CREATED' ORDER BY created_at, id LIMIT ?",
                JdbcDispatchRepository::mapOrder, limit);
    }

    @Override
    public Optional<DeliveryOrder> findCreatedOrder(UUID orderId) {
        return jdbc.query(orderSelect() + " WHERE o.id = ? AND o.status = 'CREATED'",
                JdbcDispatchRepository::mapOrder, uuidBytes(orderId)).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean markWaitingDispatch(UUID orderId, long expectedVersion, Instant now) {
        Instant dispatchAt = now.truncatedTo(ChronoUnit.MILLIS);
        int changed = jdbc.update("""
                UPDATE delivery_order
                SET status = 'WAITING_DISPATCH', version = version + 1, dispatch_round = 1,
                    next_dispatch_at = ?, updated_at = ?
                WHERE id = ? AND status = 'CREATED' AND version = ?
                """, Timestamp.from(dispatchAt), Timestamp.from(dispatchAt), uuidBytes(orderId), expectedVersion);
        if (changed == 0) {
            return false;
        }
        DeliveryOrder order = findById(orderId).orElseThrow();
        String payload = "{\"orderId\":\"" + orderId + "\",\"regionId\":\""
                + escape(order.regionId()) + "\",\"orderVersion\":" + order.version()
                + ",\"round\":1}";
        appendOrderEvent(orderId, order.version(), "order.dispatch_requested", payload, dispatchAt);
        appendOrderOutbox(orderId, order.version(), "order.dispatch_requested",
                "dispatch:" + orderId + ":1", payload, dispatchAt, dispatchAt);
        return true;
    }

    @Override
    public List<DeliveryOrder> findWaitingDispatch(int limit) {
        return jdbc.query(orderSelect() + " WHERE o.status = 'WAITING_DISPATCH' AND o.deadline > CURRENT_TIMESTAMP "
                        + "AND o.next_dispatch_at <= CURRENT_TIMESTAMP AND s.phase = 'IDLE' "
                        + "ORDER BY o.next_dispatch_at, o.created_at, o.id LIMIT ?",
                JdbcDispatchRepository::mapOrder, limit);
    }

    @Override
    public Optional<DeliveryOrder> findWaitingOrder(UUID orderId, Instant now) {
        return jdbc.query(orderSelect() + " WHERE o.id = ? AND o.status = 'WAITING_DISPATCH' "
                        + "AND o.deadline > ? AND o.next_dispatch_at <= ? AND s.phase = 'IDLE'",
                        JdbcDispatchRepository::mapOrder, uuidBytes(orderId), Timestamp.from(now), Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    @Transactional
    public boolean createOffer(DeliveryOrder expectedOrder, CourierPresence expectedCourier,
                               DispatchCandidate candidate, UUID offerId, String tokenHash,
                               String tokenCiphertext, List<DispatchCandidate> rankedCandidates,
                               Instant now, Instant expiresAt, DispatchPolicyProvider.PolicySelection policy) {
        DeliveryOrder order = findForUpdate(expectedOrder.id()).orElse(null);
        if (order == null || order.status() != OrderStatus.WAITING_DISPATCH
                || order.version() != expectedOrder.version() || !order.deadline().isAfter(now)) {
            return false;
        }
        List<String> slotRows = jdbc.query("SELECT phase FROM order_dispatch_slot WHERE order_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), uuidBytes(order.id()));
        if (slotRows.isEmpty() || !"IDLE".equals(slotRows.get(0))) {
            return false;
        }

        List<CourierPresence> courierRows = lockCourier(expectedCourier.courierId());
        if (courierRows.isEmpty()) {
            return false;
        }
        CourierPresence courier = courierRows.get(0);
        if (!"ONLINE".equals(courierState(expectedCourier.courierId()))
                || !order.regionId().equals(courier.regionId())
                || courier.presenceGeneration() != expectedCourier.presenceGeneration()
                || courier.sequence() != expectedCourier.sequence()
                || courier.lastHeartbeatAt() == null
                || !courier.lastHeartbeatAt().plusSeconds(policy.policy().heartbeatTimeoutSeconds()).isAfter(now)) {
            return false;
        }

        List<CapacityRow> capacityRows = jdbc.query("""
                SELECT capacity_limit, committed_count, reserved_count, version
                FROM courier_capacity WHERE courier_id = ? FOR UPDATE
                """, (rs, row) -> new CapacityRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)),
                uuidBytes(courier.courierId()));
        if (capacityRows.isEmpty()) {
            return false;
        }
        CapacityRow capacity = capacityRows.get(0);
        if (capacity.committed() + capacity.reserved() >= capacity.limit()
                || capacity.version() != expectedCourier.capacityVersion()) {
            return false;
        }

        int round = jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?",
                Integer.class, uuidBytes(order.id()));
        int reserved = jdbc.update("""
                UPDATE courier_capacity SET reserved_count = reserved_count + 1,
                    version = version + 1, updated_at = ?
                WHERE courier_id = ? AND version = ? AND committed_count + reserved_count < capacity_limit
                """, Timestamp.from(now), uuidBytes(courier.courierId()), capacity.version());
        if (reserved != 1) {
            throw new IllegalStateException("courier capacity changed while creating offer");
        }
        int orderChanged = jdbc.update("""
                UPDATE delivery_order SET status = 'OFFERED', version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'WAITING_DISPATCH' AND version = ?
                """, Timestamp.from(now), uuidBytes(order.id()), order.version());
        if (orderChanged != 1) {
            throw new IllegalStateException("order changed while creating offer");
        }
        jdbc.update("""
                INSERT INTO dispatch_offer
                    (id, order_id, courier_id, courier_presence_generation, round_no, token_hash,
                     token_ciphertext, status, expires_at, order_version, policy_id, policy_version,
                     policy_snapshot, estimator_version, score_detail, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OFFERED', ?, ?, ?, ?, ?, 'demo-haversine-v1', ?, ?, ?)
                """, uuidBytes(offerId), uuidBytes(order.id()), uuidBytes(courier.courierId()),
                courier.presenceGeneration(), round, tokenHash, tokenCiphertext, Timestamp.from(expiresAt),
                order.version() + 1, uuidBytes(policy.id()), policy.version(), policy.configJson(),
                candidateSnapshot(candidate), Timestamp.from(now), Timestamp.from(now));
        int slotChanged = jdbc.update("""
                UPDATE order_dispatch_slot SET phase = 'OFFERED', offer_id = ?, courier_id = ?,
                    slot_version = slot_version + 1, updated_at = ? WHERE order_id = ? AND phase = 'IDLE'
                """, uuidBytes(offerId), uuidBytes(courier.courierId()), Timestamp.from(now), uuidBytes(order.id()));
        if (slotChanged != 1) {
            throw new IllegalStateException("order dispatch slot changed while creating offer");
        }
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dispatch_attempt
                    (id, order_id, offer_id, round_no, order_version, policy_id, policy_version, policy_snapshot,
                     estimator_version, outcome, candidate_count, selected_courier_id, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'demo-haversine-v1', 'OFFER_CREATED', ?, ?, ?, ?)
                """, uuidBytes(attemptId), uuidBytes(order.id()), uuidBytes(offerId), round, order.version(),
                uuidBytes(policy.id()), policy.version(), policy.configJson(), rankedCandidates.size(),
                uuidBytes(candidate.courierId()), Timestamp.from(now), Timestamp.from(now));
        insertCandidateDecisions(attemptId, rankedCandidates, candidate.courierId());

        long capacityVersion = capacity.version() + 1;
        int reservedAfter = capacity.reserved() + 1;
        String payload = "{\"offerId\":\"" + offerId + "\",\"orderId\":\"" + order.id()
                + "\",\"courierId\":\"" + courier.courierId() + "\",\"expiresAt\":\"" + expiresAt
                + "\",\"orderVersion\":" + (order.version() + 1) + ",\"capacityVersion\":"
                + capacityVersion + ",\"committedCount\":" + capacity.committed() + ",\"reservedCount\":"
                + reservedAfter + ",\"capacityLimit\":" + capacity.limit() + "}";
        appendOrderEvent(order.id(), order.version() + 1, "offer.created", payload, now);
        appendOrderOutbox(order.id(), order.version() + 1, "offer.created",
                "offer:" + offerId + ":created", payload, now, now);
        String capacityPayload = capacityPayload(offerId, capacityVersion, capacity.committed(), reservedAfter,
                capacity.limit(), "OFFER_CREATED");
        appendCourierOutbox(courier.courierId(), capacityVersion,
                "capacity:" + courier.courierId() + ":" + capacityVersion, capacityPayload, now);
        return true;
    }

    @Override
    @Transactional
    public void recordNoCandidate(DeliveryOrder expectedOrder, List<DispatchCandidate> candidates, String outcome,
                                  Instant attemptedAt, DispatchPolicyProvider.PolicySelection policy) {
        DeliveryOrder order = findForUpdate(expectedOrder.id()).orElse(null);
        if (order == null || order.status() != OrderStatus.WAITING_DISPATCH
                || order.version() != expectedOrder.version()) {
            return;
        }
        int round = jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?",
                Integer.class, uuidBytes(order.id()));
        boolean exhausted = round >= policy.policy().maxRounds();
        Instant retryAt = exhausted ? null : attemptedAt.plusSeconds(policy.policy().retryDelaySeconds(round));
        int changed = jdbc.update("""
                UPDATE delivery_order SET status = ?, version = version + 1,
                    dispatch_round = CASE WHEN ? THEN dispatch_round ELSE dispatch_round + 1 END,
                    next_dispatch_at = ?, updated_at = ?
                WHERE id = ? AND status = 'WAITING_DISPATCH' AND version = ?
                """, exhausted ? OrderStatus.OPS_ESCALATED.name() : OrderStatus.WAITING_DISPATCH.name(),
                exhausted, exhausted ? null : Timestamp.from(retryAt), Timestamp.from(attemptedAt),
                uuidBytes(order.id()), order.version());
        if (changed != 1) return;
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dispatch_attempt
                    (id, order_id, round_no, order_version, policy_id, policy_version, policy_snapshot,
                     estimator_version, outcome, candidate_count, exclusion_summary, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'demo-haversine-v1', ?, ?, ?, ?, ?)
                """, uuidBytes(attemptId), uuidBytes(order.id()), round, order.version(), uuidBytes(policy.id()),
                policy.version(), policy.configJson(), exhausted ? "OPS_ESCALATION" : outcome, candidates.size(),
                exhausted ? "{\"reason\":\"MAX_DISPATCH_ROUNDS\"}" : "{\"reason\":\"NO_FEASIBLE_CANDIDATE\"}",
                Timestamp.from(attemptedAt), Timestamp.from(attemptedAt));
        insertCandidateDecisions(attemptId, candidates, null);
        DeliveryOrder after = findById(order.id()).orElseThrow();
        String eventType = exhausted ? "dispatch.ops_escalated" : "dispatch.no_candidate";
        String payload = "{\"orderId\":\"" + order.id() + "\",\"outcome\":\""
                + (exhausted ? "OPS_ESCALATION" : outcome) + "\",\"round\":" + round
                + ",\"orderVersion\":" + after.version() + ",\"nextDispatchAt\":"
                + (exhausted ? "null" : "\"" + retryAt + "\"") + "}";
        appendOrderEvent(order.id(), after.version(), eventType, payload, attemptedAt);
        appendOrderOutbox(order.id(), after.version(), eventType,
                (exhausted ? "dispatch:escalated:" : "dispatch:") + order.id() + ":" + after.version(),
                payload, attemptedAt, exhausted ? attemptedAt : retryAt);
    }

    @Override
    @Transactional
    public void recordDispatchUnavailable(DeliveryOrder expectedOrder, Instant attemptedAt, Instant retryAt,
                                         DispatchPolicyProvider.PolicySelection policy) {
        DeliveryOrder order = findForUpdate(expectedOrder.id()).orElse(null);
        if (order == null || order.status() != OrderStatus.WAITING_DISPATCH
                || order.version() != expectedOrder.version()) return;
        int round = jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?",
                Integer.class, uuidBytes(order.id()));
        int changed = jdbc.update("""
                UPDATE delivery_order SET version = version + 1, next_dispatch_at = ?, updated_at = ?
                WHERE id = ? AND status = 'WAITING_DISPATCH' AND version = ?
                """, Timestamp.from(retryAt), Timestamp.from(attemptedAt), uuidBytes(order.id()), order.version());
        if (changed != 1) return;
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dispatch_attempt (id, order_id, round_no, order_version, policy_id, policy_version,
                    policy_snapshot, estimator_version, outcome, candidate_count, exclusion_summary, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'demo-haversine-v1', 'REDIS_UNAVAILABLE', 0,
                    '{\"reason\":\"REDIS_PING_FAILED\"}', ?, ?)
                """, uuidBytes(attemptId), uuidBytes(order.id()), round, order.version(), uuidBytes(policy.id()),
                policy.version(), policy.configJson(), Timestamp.from(attemptedAt), Timestamp.from(attemptedAt));
        DeliveryOrder after = findById(order.id()).orElseThrow();
        String payload = "{\"orderId\":\"" + order.id() + "\",\"outcome\":\"REDIS_UNAVAILABLE\",\"round\":"
                + round + ",\"orderVersion\":" + after.version() + ",\"retryAt\":\"" + retryAt + "\"}";
        appendOrderEvent(order.id(), after.version(), "dispatch.redis_unavailable", payload, attemptedAt);
        appendOrderOutbox(order.id(), after.version(), "dispatch.redis_unavailable",
                "dispatch:redis-unavailable:" + order.id() + ":" + after.version(), payload, attemptedAt, retryAt);
    }

    @Override
    public Optional<DispatchOffer> findOffer(UUID offerId) {
        return jdbc.query(offerSelect() + " WHERE id = ?", JdbcDispatchRepository::mapOffer,
                uuidBytes(offerId)).stream().findFirst();
    }

    @Override
    public Optional<DispatchOffer> findActiveOffer(UUID orderId) {
        return jdbc.query(offerSelect() + " WHERE order_id = ? AND status = 'OFFERED'",
                JdbcDispatchRepository::mapOffer, uuidBytes(orderId)).stream().findFirst();
    }

    @Override
    public List<DispatchOffer> findActiveOffersForCourier(UUID courierId, Instant now) {
        return jdbc.query(offerSelect() + " WHERE courier_id = ? AND status = 'OFFERED' AND expires_at > ? "
                        + "ORDER BY created_at",
                JdbcDispatchRepository::mapOffer, uuidBytes(courierId), Timestamp.from(now));
    }

    @Override
    @Transactional(noRollbackFor = DispatchOfferConflictException.class)
    public OfferCommandResult respondToOffer(UUID courierId, UUID offerId, String tokenHash,
                                             long expectedOrderVersion, boolean accept, String reason, Instant now) {
        OfferIdentity identity = jdbc.query("SELECT order_id, courier_id FROM dispatch_offer WHERE id = ?",
                        (rs, row) -> new OfferIdentity(uuid(rs.getBytes(1)), uuid(rs.getBytes(2))), uuidBytes(offerId))
                .stream().findFirst().orElseThrow(() -> new DispatchOfferNotFoundException(offerId));
        if (!identity.courierId().equals(courierId)) {
            throw new DispatchOfferConflictException("offer belongs to a different courier");
        }
        DeliveryOrder order = findForUpdate(identity.orderId()).orElseThrow();
        Slot slot = lockSlot(order.id());
        List<CourierPresence> courierRows = lockCourier(courierId);
        if (courierRows.isEmpty()) {
            throw new DispatchOfferConflictException("courier does not exist");
        }
        List<CapacityRow> capacityRows = lockCapacity(courierId);
        List<DispatchOffer> offers = jdbc.query(offerSelect() + " WHERE id = ? FOR UPDATE",
                JdbcDispatchRepository::mapOffer, uuidBytes(offerId));
        DispatchOffer offer = offers.isEmpty() ? null : offers.get(0);
        if (offer == null || !MessageDigest.isEqual(offer.tokenHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                tokenHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new DispatchOfferConflictException("offer token is invalid");
        }
        if (offer.status() != DispatchOfferStatus.OFFERED) {
            return new OfferCommandResult(order, offer, false);
        }
        if (order.status() != OrderStatus.OFFERED || order.version() != expectedOrderVersion
                || offer.orderVersion() != expectedOrderVersion || !offer.id().equals(slot.offerId())) {
            throw new DispatchOfferConflictException("offer is no longer active for this order version");
        }
        if (!offer.expiresAt().isAfter(now)) {
            throw new DispatchOfferConflictException("offer has expired");
        }
        if (capacityRows.isEmpty()) {
            throw new DispatchOfferConflictException("courier capacity ledger is unavailable");
        }
        CapacityRow capacity = capacityRows.get(0);
        CourierPresence courier = courierRows.get(0);
        DispatchPolicy policy = policyFor(offer, order.regionId()).policy();
        if (accept && (courier.presenceGeneration() != offer.courierPresenceGeneration()
                || !"ONLINE".equals(courierState(courierId)) || courier.lastHeartbeatAt() == null
                || !courier.lastHeartbeatAt().plusSeconds(policy.heartbeatTimeoutSeconds()).isAfter(now))) {
            finishOfferFailure(order, slot, courier, capacity, offer, DispatchOfferStatus.SUPERSEDED,
                    "COURIER_SESSION_CHANGED", "offer.superseded", "COURIER_SESSION_CHANGED", now);
            throw new DispatchOfferConflictException("courier session changed after the offer was issued");
        }
        if (!accept) {
            return finishOfferFailure(order, slot, courier, capacity, offer, DispatchOfferStatus.REJECTED,
                    "OFFER_REJECTED", "offer.rejected", reason, now);
        }
        int changedOffer = jdbc.update("""
                UPDATE dispatch_offer SET status = ?, token_ciphertext = NULL, updated_at = ?
                WHERE id = ? AND status = 'OFFERED'
                """, DispatchOfferStatus.ACCEPTED.name(), Timestamp.from(now), uuidBytes(offerId));
        if (changedOffer != 1) {
            return new OfferCommandResult(order, offer, false);
        }
        int capacityChanged = jdbc.update("""
                UPDATE courier_capacity SET reserved_count = reserved_count - 1,
                    committed_count = committed_count + 1, version = version + 1, updated_at = ?
                WHERE courier_id = ? AND version = ? AND reserved_count > 0
                """, Timestamp.from(now), uuidBytes(courierId), capacity.version());
        int slotChanged = jdbc.update("""
                UPDATE order_dispatch_slot SET phase = 'ASSIGNED', slot_version = slot_version + 1,
                    updated_at = ? WHERE order_id = ? AND offer_id = ? AND phase = 'OFFERED'
                """, Timestamp.from(now), uuidBytes(order.id()), uuidBytes(offerId));
        if (capacityChanged != 1 || slotChanged != 1) {
            throw new IllegalStateException("offer capacity or dispatch slot changed while locked");
        }
        jdbc.update("""
                INSERT INTO dispatch_assignment
                    (id, order_id, courier_id, offer_id, status, accepted_at, version)
                VALUES (?, ?, ?, ?, 'ACCEPTED', ?, 1)
                """, uuidBytes(UUID.randomUUID()), uuidBytes(order.id()), uuidBytes(courierId),
                uuidBytes(offerId), Timestamp.from(now));
        jdbc.update("""
                UPDATE delivery_order SET status = 'ACCEPTED', version = version + 1,
                    updated_at = ? WHERE id = ? AND version = ?
                """, Timestamp.from(now), uuidBytes(order.id()), order.version());
        DeliveryOrder after = findById(order.id()).orElseThrow();
        long capVersionAfter = capacity.version() + 1;
        int reservedAfter = capacity.reserved() - 1;
        int committedAfter = capacity.committed() + 1;
        String eventType = "offer.accepted";
        String payload = "{\"offerId\":\"" + offerId + "\",\"orderId\":\"" + order.id()
                + "\",\"courierId\":\"" + courierId + "\",\"reason\":"
                + (reason == null ? "null" : "\"" + escape(reason) + "\"")
                + ",\"orderVersion\":" + after.version() + "}";
        appendOrderEvent(order.id(), after.version(), eventType, payload, now);
        appendOrderOutbox(order.id(), after.version(), eventType,
                "offer:" + offerId + ":ACCEPTED", payload, now, now);
        appendCourierOutbox(courierId, capVersionAfter,
                "capacity:" + courierId + ":" + capVersionAfter,
                capacityPayload(offerId, capVersionAfter, committedAfter, reservedAfter, capacity.limit(),
                        "ACCEPTED"), now);
        DispatchOffer afterOffer = findOffer(offerId).orElseThrow();
        return new OfferCommandResult(after, afterOffer, true);
    }

    @Override
    @Transactional
    public DeliveryOrder transitionAssignedOrder(UUID courierId, UUID orderId, long expectedVersion,
                                                  OrderStatus expectedStatus, OrderStatus targetStatus,
                                                  UUID eventId, Instant now) {
        if (eventExists(eventId, orderId)) {
            return findById(orderId).orElseThrow();
        }
        DeliveryOrder order = findForUpdate(orderId).orElseThrow();
        Slot slot = lockSlot(orderId);
        if (order.version() != expectedVersion || order.status() != expectedStatus) {
            throw new OrderVersionConflictException(orderId, order.version(), order.status());
        }
        if (!"ASSIGNED".equals(slot.phase())
                || !courierId.equals(slot.courierId())) {
            throw new DispatchOfferConflictException("courier is not assigned to this order");
        }
        CapacityRow capacity = lockCapacity(courierId).get(0);
        int changed = jdbc.update("""
                UPDATE delivery_order SET status = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND status = ? AND version = ?
                """, targetStatus.name(), Timestamp.from(now), uuidBytes(orderId), expectedStatus.name(), expectedVersion);
        if (changed != 1) {
            DeliveryOrder latest = findById(orderId).orElseThrow();
            throw new OrderVersionConflictException(orderId, latest.version(), latest.status());
        }
        if (targetStatus == OrderStatus.PICKED_UP) {
            jdbc.update("UPDATE dispatch_assignment SET status = 'PICKED_UP', version = version + 1 WHERE order_id = ? AND courier_id = ? AND released_at IS NULL",
                    uuidBytes(orderId), uuidBytes(courierId));
        } else {
            jdbc.update("UPDATE dispatch_assignment SET status = 'DELIVERED', released_at = ?, version = version + 1 WHERE order_id = ? AND courier_id = ? AND released_at IS NULL",
                    Timestamp.from(now), uuidBytes(orderId), uuidBytes(courierId));
            jdbc.update("UPDATE order_dispatch_slot SET phase = 'CLOSED', slot_version = slot_version + 1, updated_at = ? WHERE order_id = ?",
                    Timestamp.from(now), uuidBytes(orderId));
            jdbc.update("""
                    UPDATE courier_capacity SET committed_count = committed_count - 1,
                        version = version + 1, updated_at = ?
                    WHERE courier_id = ? AND version = ? AND committed_count > 0
                    """, Timestamp.from(now), uuidBytes(courierId), capacity.version());
            appendCourierOutbox(courierId, capacity.version() + 1,
                    "capacity:" + courierId + ":" + (capacity.version() + 1),
                    capacityPayload(null, capacity.version() + 1, capacity.committed() - 1,
                            capacity.reserved(), capacity.limit(), "DELIVERED"), now);
        }
        DeliveryOrder after = findById(orderId).orElseThrow();
        String payload = "{\"orderId\":\"" + orderId + "\",\"courierId\":\"" + courierId
                + "\",\"from\":\"" + expectedStatus + "\",\"to\":\"" + targetStatus
                + "\",\"eventId\":\"" + eventId + "\"}";
        appendOrderEvent(eventId, orderId, after.version(), "order." + targetStatus.name().toLowerCase(), payload, now);
        appendOrderOutbox(orderId, after.version(), "order." + targetStatus.name().toLowerCase(),
                "order:" + orderId + ":" + targetStatus + ":" + after.version(), payload, now, now);
        return after;
    }

    @Override
    public List<DispatchOffer> findExpiredOffers(Instant now, int limit) {
        return jdbc.query(offerSelect() + " WHERE status = 'OFFERED' AND expires_at <= ? ORDER BY expires_at LIMIT ?",
                JdbcDispatchRepository::mapOffer, Timestamp.from(now), limit);
    }

    @Override
    @Transactional
    public OfferCommandResult expireOffer(UUID offerId, Instant now) {
        OfferIdentity identity = jdbc.query("SELECT order_id, courier_id FROM dispatch_offer WHERE id = ?",
                        (rs, row) -> new OfferIdentity(uuid(rs.getBytes(1)), uuid(rs.getBytes(2))), uuidBytes(offerId))
                .stream().findFirst().orElseThrow(() -> new DispatchOfferNotFoundException(offerId));
        DeliveryOrder order = findForUpdate(identity.orderId()).orElseThrow();
        Slot slot = lockSlot(order.id());
        List<CourierPresence> courierRows = lockCourier(identity.courierId());
        List<CapacityRow> capacityRows = lockCapacity(identity.courierId());
        List<DispatchOffer> offers = jdbc.query(offerSelect() + " WHERE id = ? FOR UPDATE",
                JdbcDispatchRepository::mapOffer, uuidBytes(offerId));
        DispatchOffer offer = offers.get(0);
        if (offer.status() != DispatchOfferStatus.OFFERED || offer.expiresAt().isAfter(now)
                || !offer.id().equals(slot.offerId()) || order.status() != OrderStatus.OFFERED) {
            return new OfferCommandResult(order, offer, false);
        }
        CapacityRow capacity = capacityRows.get(0);
        return finishOfferFailure(order, slot, courierRows.get(0), capacity, offer,
                DispatchOfferStatus.EXPIRED, "OFFER_TIMEOUT", "offer.expired", null, now);
    }

    private OfferCommandResult finishOfferFailure(DeliveryOrder order, Slot slot, CourierPresence courier,
                                                  CapacityRow capacity, DispatchOffer offer,
                                                  DispatchOfferStatus offerStatus, String attemptOutcome,
                                                  String eventType, String reason, Instant now) {
        DispatchPolicyProvider.PolicySelection policySelection = policyFor(offer, order.regionId());
        DispatchPolicy policy = policySelection.policy();
        boolean exhausted = offer.round() >= policy.maxRounds();
        Instant retryAt = exhausted ? null : now.plusSeconds(policy.retryDelaySeconds(offer.round()));
        int changedOffer = jdbc.update("""
                UPDATE dispatch_offer SET status = ?, invalidation_reason = ?, token_ciphertext = NULL, updated_at = ?
                WHERE id = ? AND status = 'OFFERED'
                """, offerStatus.name(), attemptOutcome, Timestamp.from(now), uuidBytes(offer.id()));
        if (changedOffer != 1) return new OfferCommandResult(order, offer, false);
        int changedCapacity = jdbc.update("""
                UPDATE courier_capacity SET reserved_count = reserved_count - 1,
                    version = version + 1, updated_at = ?
                WHERE courier_id = ? AND version = ? AND reserved_count > 0
                """, Timestamp.from(now), uuidBytes(courier.courierId()), capacity.version());
        int changedSlot = jdbc.update("""
                UPDATE order_dispatch_slot SET phase = 'IDLE', offer_id = NULL, courier_id = NULL,
                    slot_version = slot_version + 1, updated_at = ? WHERE order_id = ? AND offer_id = ?
                """, Timestamp.from(now), uuidBytes(order.id()), uuidBytes(offer.id()));
        if (changedCapacity != 1 || changedSlot != 1) {
            throw new IllegalStateException("offer reservation or slot was inconsistent");
        }
        jdbc.update("""
                UPDATE dispatch_attempt SET outcome = ?, finished_at = ?
                WHERE offer_id = ? AND outcome = 'OFFER_CREATED'
                """, attemptOutcome, Timestamp.from(now), uuidBytes(offer.id()));
        OrderStatus nextStatus = exhausted ? OrderStatus.OPS_ESCALATED : OrderStatus.WAITING_DISPATCH;
        int changedOrder = jdbc.update("""
                UPDATE delivery_order SET status = ?, version = version + 1,
                    dispatch_round = CASE WHEN ? THEN dispatch_round ELSE dispatch_round + 1 END,
                    next_dispatch_at = ?, updated_at = ?
                WHERE id = ? AND status = 'OFFERED' AND version = ?
                """, nextStatus.name(), exhausted, retryAt == null ? null : Timestamp.from(retryAt),
                Timestamp.from(now), uuidBytes(order.id()), order.version());
        if (changedOrder != 1) throw new IllegalStateException("order changed while its offer was locked");

        DeliveryOrder after = findById(order.id()).orElseThrow();
        String payload = "{\"offerId\":\"" + offer.id() + "\",\"orderId\":\"" + order.id()
                + "\",\"courierId\":\"" + courier.courierId() + "\",\"reason\":"
                + (reason == null ? "null" : "\"" + escape(reason) + "\"")
                + ",\"outcome\":\"" + attemptOutcome + "\",\"orderVersion\":" + after.version()
                + ",\"round\":" + offer.round() + ",\"nextDispatchAt\":"
                + (retryAt == null ? "null" : "\"" + retryAt + "\"") + "}";
        appendOrderEvent(order.id(), after.version(), eventType, payload, now);
        appendOrderOutbox(order.id(), after.version(), eventType,
                "offer:" + offer.id() + ":" + offerStatus.name(), payload, now, now);
        long capacityVersion = capacity.version() + 1;
        appendCourierOutbox(courier.courierId(), capacityVersion,
                "capacity:" + courier.courierId() + ":" + capacityVersion,
                capacityPayload(offer.id(), capacityVersion, capacity.committed(), capacity.reserved() - 1,
                        capacity.limit(), attemptOutcome), now);
        if (exhausted) {
            appendOrderEvent(order.id(), after.version(), "dispatch.ops_escalated", payload, now);
            appendOrderOutbox(order.id(), after.version(), "dispatch.ops_escalated",
                    "dispatch:escalated:" + order.id() + ":" + after.version(), payload, now, now);
        } else {
            appendOrderOutbox(order.id(), after.version(), "order.dispatch_requested",
                    "dispatch:" + order.id() + ":" + after.version(), payload, now, retryAt);
        }
        return new OfferCommandResult(after, findOffer(offer.id()).orElseThrow(), true);
    }

    @Override
    @Transactional
    public void invalidateStaleCourierOffers(UUID courierId, long currentGeneration, Instant now, String reason) {
        List<StaleOfferRef> staleOffers = jdbc.query("""
                SELECT id, order_id FROM dispatch_offer
                WHERE courier_id = ? AND status = 'OFFERED'
                  AND (? < 0 OR courier_presence_generation <> ?)
                ORDER BY order_id, created_at
                """, (rs, row) -> new StaleOfferRef(uuid(rs.getBytes(1)), uuid(rs.getBytes(2))),
                uuidBytes(courierId), currentGeneration, currentGeneration);
        for (StaleOfferRef ref : staleOffers) {
            DeliveryOrder order = findForUpdate(ref.orderId()).orElse(null);
            if (order == null || order.status() != OrderStatus.OFFERED) continue;
            Slot slot = lockSlot(order.id());
            if (!ref.offerId().equals(slot.offerId())) continue;
            List<CourierPresence> courierRows = lockCourier(courierId);
            List<CapacityRow> capacityRows = lockCapacity(courierId);
            List<DispatchOffer> offerRows = jdbc.query(offerSelect() + " WHERE id = ? FOR UPDATE",
                    JdbcDispatchRepository::mapOffer, uuidBytes(ref.offerId()));
            if (courierRows.isEmpty() || capacityRows.isEmpty() || offerRows.isEmpty()) continue;
            DispatchOffer offer = offerRows.get(0);
            if (offer.status() == DispatchOfferStatus.OFFERED) {
                finishOfferFailure(order, slot, courierRows.get(0), capacityRows.get(0), offer,
                        DispatchOfferStatus.SUPERSEDED, reason, "offer.superseded", reason, now);
            }
        }
    }

    @Override
    @Transactional
    public void releaseOrderResources(UUID orderId, String reason, Instant now) {
        DeliveryOrder order = findForUpdate(orderId).orElse(null);
        if (order == null) return;
        Slot slot = lockSlot(orderId);
        if ("OFFERED".equals(slot.phase()) && slot.offerId() != null && slot.courierId() != null) {
            List<CourierPresence> couriers = lockCourier(slot.courierId());
            List<CapacityRow> capacities = lockCapacity(slot.courierId());
            List<DispatchOffer> offers = jdbc.query(offerSelect() + " WHERE id = ? FOR UPDATE",
                    JdbcDispatchRepository::mapOffer, uuidBytes(slot.offerId()));
            if (!couriers.isEmpty() && !capacities.isEmpty() && !offers.isEmpty()
                    && offers.get(0).status() == DispatchOfferStatus.OFFERED) {
                DispatchOffer offer = offers.get(0);
                CapacityRow capacity = capacities.get(0);
                jdbc.update("""
                        UPDATE dispatch_offer SET status = 'SUPERSEDED', invalidation_reason = ?,
                            token_ciphertext = NULL, updated_at = ? WHERE id = ? AND status = 'OFFERED'
                        """, reason, Timestamp.from(now), uuidBytes(offer.id()));
                jdbc.update("""
                        UPDATE dispatch_attempt SET outcome = ?, finished_at = ?
                        WHERE offer_id = ? AND outcome = 'OFFER_CREATED'
                        """, reason, Timestamp.from(now), uuidBytes(offer.id()));
                int released = jdbc.update("""
                        UPDATE courier_capacity SET reserved_count = reserved_count - 1,
                            version = version + 1, updated_at = ?
                        WHERE courier_id = ? AND version = ? AND reserved_count > 0
                        """, Timestamp.from(now), uuidBytes(slot.courierId()), capacity.version());
                if (released != 1) throw new IllegalStateException("reserved capacity was inconsistent");
                long capacityVersion = capacity.version() + 1;
                appendCourierOutbox(slot.courierId(), capacityVersion,
                        "capacity:" + slot.courierId() + ":" + capacityVersion,
                        capacityPayload(offer.id(), capacityVersion, capacity.committed(), capacity.reserved() - 1,
                                capacity.limit(), reason), now);
                String payload = "{\"orderId\":\"" + orderId + "\",\"offerId\":\"" + offer.id()
                        + "\",\"reason\":\"" + escape(reason) + "\"}";
                appendOrderEvent(orderId, order.version(), "offer.superseded", payload, now);
                appendOrderOutbox(orderId, order.version(), "offer.superseded",
                        "offer:" + offer.id() + ":superseded", payload, now, now);
            }
        }
        if ("ASSIGNED".equals(slot.phase()) && slot.courierId() != null) {
            List<CourierPresence> couriers = lockCourier(slot.courierId());
            List<CapacityRow> capacities = lockCapacity(slot.courierId());
            if (!couriers.isEmpty() && !capacities.isEmpty()) {
                CapacityRow capacity = capacities.get(0);
                int assignmentChanged = jdbc.update("""
                        UPDATE dispatch_assignment SET status = 'RELEASED', released_at = ?, release_reason = ?,
                            version = version + 1 WHERE order_id = ? AND courier_id = ?
                            AND status IN ('ACCEPTED', 'PICKED_UP') AND released_at IS NULL
                        """, Timestamp.from(now), reason, uuidBytes(orderId), uuidBytes(slot.courierId()));
                if (assignmentChanged == 1) {
                    int released = jdbc.update("""
                            UPDATE courier_capacity SET committed_count = committed_count - 1,
                                version = version + 1, updated_at = ?
                            WHERE courier_id = ? AND version = ? AND committed_count > 0
                            """, Timestamp.from(now), uuidBytes(slot.courierId()), capacity.version());
                    if (released != 1) throw new IllegalStateException("committed capacity was inconsistent");
                    long capacityVersion = capacity.version() + 1;
                    appendCourierOutbox(slot.courierId(), capacityVersion,
                            "capacity:" + slot.courierId() + ":" + capacityVersion,
                            capacityPayload(null, capacityVersion, capacity.committed() - 1, capacity.reserved(),
                                    capacity.limit(), reason), now);
                }
            }
        }
    }

    @Override
    @Transactional
    public void reassessPreparationOffer(UUID orderId, Instant prepEta, boolean ready, Instant now) {
        DeliveryOrder order = findForUpdate(orderId).orElse(null);
        if (order == null) return;
        if (order.status() == OrderStatus.WAITING_DISPATCH) {
            DispatchPolicyProvider.PolicySelection selection = policies.active(order.regionId());
            int round = jdbc.queryForObject("SELECT dispatch_round FROM delivery_order WHERE id = ?",
                    Integer.class, uuidBytes(orderId));
            boolean canAdvanceRound = round < selection.policy().maxRounds();
            int nextRound = canAdvanceRound ? round + 1 : round;
            Instant retryAt = now.plusSeconds(canAdvanceRound
                    ? selection.policy().retryDelaySeconds(round) : 0);
            int changed = jdbc.update("""
                    UPDATE delivery_order
                    SET dispatch_round = ?, next_dispatch_at = ?, version = version + 1, updated_at = ?
                    WHERE id = ? AND status = 'WAITING_DISPATCH' AND version = ?
                    """, nextRound, Timestamp.from(retryAt), Timestamp.from(now),
                    uuidBytes(orderId), order.version());
            if (changed != 1) return;
            DeliveryOrder after = findById(orderId).orElseThrow();
            String payload = "{\"orderId\":\"" + orderId + "\",\"prepEta\":\"" + prepEta
                    + "\",\"ready\":" + ready + ",\"round\":" + nextRound
                    + ",\"orderVersion\":" + after.version() + ",\"policyId\":\""
                    + selection.id() + "\",\"policyVersion\":" + selection.version()
                    + ",\"nextDispatchAt\":\"" + retryAt + "\"}";
            appendOrderEvent(order.id(), after.version(), "order.dispatch_requested", payload, now);
            appendOrderOutbox(order.id(), after.version(), "order.dispatch_requested",
                    "dispatch:" + order.id() + ":prep:" + after.version(), payload, now, retryAt);
            return;
        }
        if (order.status() != OrderStatus.OFFERED) return;
        Slot slot = lockSlot(orderId);
        if (!"OFFERED".equals(slot.phase()) || slot.offerId() == null || slot.courierId() == null) return;
        List<CourierPresence> courierRows = lockCourier(slot.courierId());
        List<CapacityRow> capacityRows = lockCapacity(slot.courierId());
        List<DispatchOffer> offers = jdbc.query(offerSelect() + " WHERE id = ? FOR UPDATE",
                JdbcDispatchRepository::mapOffer, uuidBytes(slot.offerId()));
        if (courierRows.isEmpty() || capacityRows.isEmpty() || offers.isEmpty()) return;
        CourierPresence courier = courierRows.get(0);
        CapacityRow capacity = capacityRows.get(0);
        DispatchOffer offer = offers.get(0);
        if (offer.status() != DispatchOfferStatus.OFFERED) return;
        DispatchPolicyProvider.PolicySelection selection = policyFor(offer, order.regionId());
        boolean remainsValid = offer.expiresAt().isAfter(now)
                && feasibleForUpdatedPreparation(order, courier, prepEta, ready, now, selection.policy());
        if (!remainsValid) {
            finishOfferFailure(order, slot, courier, capacity, offer, DispatchOfferStatus.SUPERSEDED,
                    "PREPARATION_INVALIDATED", "offer.superseded", "PREPARATION_CHANGED", now);
            return;
        }
        jdbc.update("UPDATE dispatch_offer SET order_version = ?, updated_at = ? WHERE id = ? AND status = 'OFFERED'",
                order.version(), Timestamp.from(now), uuidBytes(offer.id()));
        String payload = "{\"orderId\":\"" + order.id() + "\",\"offerId\":\"" + offer.id()
                + "\",\"orderVersion\":" + order.version() + ",\"policyId\":\"" + selection.id()
                + "\",\"policyVersion\":" + selection.version() + "}";
        appendOrderEvent(order.id(), order.version(), "offer.reassessed", payload, now);
        appendOrderOutbox(order.id(), order.version(), "offer.reassessed",
                "offer:" + offer.id() + ":reassessed:" + order.version(), payload, now, now);
    }

    private boolean feasibleForUpdatedPreparation(DeliveryOrder order, CourierPresence courier,
                                                  Instant prepEta, boolean ready, Instant now,
                                                  DispatchPolicy policy) {
        if (!order.regionId().equals(courier.regionId()) || !courier.isOnlineAt(now, policy.heartbeatTimeoutSeconds())
                || courier.latitude() == null || courier.longitude() == null) return false;
        double pickupDistance = distanceKm(courier.latitude(), courier.longitude(), order.pickupLat(), order.pickupLon()) * 1.3;
        if (pickupDistance > policy.maxPickupDistanceKm()) return false;
        long pickupSeconds = Math.round(pickupDistance / 20.0 * 3600.0);
        Instant merchantArrival = now.plusSeconds(pickupSeconds);
        Instant effectivePrepEta = ready ? now : prepEta;
        Instant pickupAt = merchantArrival.isAfter(effectivePrepEta) ? merchantArrival : effectivePrepEta;
        long deliverySeconds = Math.round(distanceKm(order.pickupLat(), order.pickupLon(),
                order.dropoffLat(), order.dropoffLon()) * 1.3 / 20.0 * 3600.0) + 120;
        return !pickupAt.plusSeconds(deliverySeconds).isAfter(order.deadline());
    }

    private DispatchPolicyProvider.PolicySelection policyFor(DispatchOffer offer, String regionId) {
        return offer.policyId() == null ? policies.active(regionId) : policies.find(offer.policyId());
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Optional<DeliveryOrder> findById(UUID id) {
        return jdbc.query(orderSelect() + " WHERE o.id = ?", JdbcDispatchRepository::mapOrder,
                uuidBytes(id)).stream().findFirst();
    }

    private Optional<DeliveryOrder> findForUpdate(UUID id) {
        return jdbc.query("""
                SELECT id, merchant_id, region_id, pickup_lat, pickup_lon, dropoff_lat, dropoff_lon,
                       prep_eta, deadline, preparation_ready, status, version, created_at, updated_at
                FROM delivery_order WHERE id = ? FOR UPDATE
                """, JdbcDispatchRepository::mapOrder, uuidBytes(id)).stream().findFirst();
    }

    private Slot lockSlot(UUID orderId) {
        return jdbc.query("""
                SELECT phase, offer_id, courier_id FROM order_dispatch_slot WHERE order_id = ? FOR UPDATE
                """, (rs, row) -> new Slot(rs.getString(1), uuidNullable(rs.getBytes(2)),
                uuidNullable(rs.getBytes(3))), uuidBytes(orderId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("order dispatch slot is missing"));
    }

    private List<CourierPresence> lockCourier(UUID courierId) {
        return jdbc.query("""
                SELECT c.id, c.region_id, c.online_state, c.presence_generation, c.heartbeat_sequence,
                       c.latitude, c.longitude, c.last_position_at, cap.capacity_limit,
                       cap.committed_count, cap.reserved_count, cap.version AS capacity_version,
                       cap.updated_at AS capacity_updated_at
                FROM courier c JOIN courier_capacity cap ON cap.courier_id = c.id
                WHERE c.id = ? FOR UPDATE
                """, JdbcDispatchRepository::mapCourier, uuidBytes(courierId));
    }

    private List<CapacityRow> lockCapacity(UUID courierId) {
        return jdbc.query("""
                SELECT capacity_limit, committed_count, reserved_count, version
                FROM courier_capacity WHERE courier_id = ? FOR UPDATE
                """, (rs, row) -> new CapacityRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)),
                uuidBytes(courierId));
    }

    private String courierState(UUID courierId) {
        return jdbc.queryForObject("SELECT online_state FROM courier WHERE id = ?", String.class, uuidBytes(courierId));
    }

    private void insertCandidateDecisions(UUID attemptId, List<DispatchCandidate> candidates, UUID selectedCourierId) {
        int rank = 1;
        for (DispatchCandidate candidate : candidates) {
            jdbc.update("""
                    INSERT INTO dispatch_candidate_decision
                        (attempt_id, courier_id, eligible, reason_code, rank_no, score, feature_snapshot)
                    VALUES (?, ?, TRUE, ?, ?, ?, ?)
                    """, uuidBytes(attemptId), uuidBytes(candidate.courierId()),
                    candidate.courierId().equals(selectedCourierId) ? "SELECTED" : "FEASIBLE",
                    rank++, candidate.score(), candidateSnapshot(candidate));
        }
    }

    private boolean eventExists(UUID eventId, UUID aggregateId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_event WHERE event_id = ? AND aggregate_id = ?",
                Integer.class, uuidBytes(eventId), uuidBytes(aggregateId));
        return count != null && count > 0;
    }

    private void appendOrderEvent(UUID orderId, long version, String eventType, String payload, Instant now) {
        appendOrderEvent(UUID.randomUUID(), orderId, version, eventType, payload, now);
    }

    private void appendOrderEvent(UUID eventId, UUID orderId, long version, String eventType,
                                  String payload, Instant now) {
        jdbc.update("""
                INSERT INTO delivery_event
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                     actor_type, occurred_at, payload_json)
                VALUES (?, 'ORDER', ?, ?, ?, 'SYSTEM', ?, ?)
                """, uuidBytes(eventId), uuidBytes(orderId), version, eventType, Timestamp.from(now), payload);
    }

    private void appendOrderOutbox(UUID orderId, long version, String eventType, String dedupe, String payload,
                                   Instant createdAt, Instant nextAttemptAt) {
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                     dedupe_key, payload_json, created_at, next_attempt_at)
                VALUES (?, ?, 'ORDER', ?, ?, ?, ?, ?, ?)
                """, uuidBytes(UUID.randomUUID()), eventType, uuidBytes(orderId), version, dedupe, payload,
                Timestamp.from(createdAt), Timestamp.from(nextAttemptAt));
    }

    private void appendCourierOutbox(UUID courierId, long version, String dedupe, String payload, Instant now) {
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                     dedupe_key, payload_json, created_at, next_attempt_at)
                VALUES (?, 'courier.capacity_changed', 'COURIER', ?, ?, ?, ?, ?, ?)
                """, uuidBytes(UUID.randomUUID()), uuidBytes(courierId), version, dedupe, payload,
                Timestamp.from(now), Timestamp.from(now));
    }

    private static String capacityPayload(UUID offerId, long version, int committed, int reserved,
                                          int limit, String transition) {
        return "{\"offerId\":" + (offerId == null ? "null" : "\"" + offerId + "\"")
                + ",\"capacityVersion\":" + version + ",\"committedCount\":" + committed
                + ",\"reservedCount\":" + reserved + ",\"capacityLimit\":" + limit
                + ",\"transition\":\"" + transition + "\"}";
    }

    private static String candidateSnapshot(DispatchCandidate candidate) {
        return "{\"score\":" + candidate.score() + ",\"distanceKm\":" + candidate.distanceKm()
                + ",\"loadRatio\":" + candidate.loadRatio() + ",\"estimatedDeliveryAt\":\""
                + candidate.estimatedDeliveryAt() + "\"}";
    }

    private static String orderSelect() {
        return """
                SELECT o.id, o.merchant_id, o.region_id, o.pickup_lat, o.pickup_lon,
                       o.dropoff_lat, o.dropoff_lon, o.prep_eta, o.deadline, o.status,
                       o.preparation_ready, o.version, o.created_at, o.updated_at
                FROM delivery_order o LEFT JOIN order_dispatch_slot s ON s.order_id = o.id
                """;
    }

    private static String offerSelect() {
        return """
                SELECT id, order_id, courier_id, courier_presence_generation, round_no, token_hash,
                       token_ciphertext, status, expires_at, order_version, policy_id, policy_version,
                       policy_snapshot, created_at, updated_at
                FROM dispatch_offer
                """;
    }

    private static DeliveryOrder mapOrder(ResultSet rs, int row) throws SQLException {
        return new DeliveryOrder(uuid(rs.getBytes("id")), uuid(rs.getBytes("merchant_id")),
                rs.getString("region_id"), rs.getBigDecimal("pickup_lat").doubleValue(),
                rs.getBigDecimal("pickup_lon").doubleValue(), rs.getBigDecimal("dropoff_lat").doubleValue(),
                rs.getBigDecimal("dropoff_lon").doubleValue(), rs.getTimestamp("prep_eta").toInstant(),
                rs.getTimestamp("deadline").toInstant(), rs.getBoolean("preparation_ready"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static CourierPresence mapCourier(ResultSet rs, int row) throws SQLException {
        Timestamp lastPosition = rs.getTimestamp("last_position_at");
        java.math.BigDecimal latitude = rs.getBigDecimal("latitude");
        java.math.BigDecimal longitude = rs.getBigDecimal("longitude");
        return new CourierPresence(uuid(rs.getBytes("id")), rs.getString("region_id"),
                com.peakdispatch.domain.courier.CourierOnlineState.valueOf(rs.getString("online_state")),
                rs.getLong("presence_generation"), rs.getLong("heartbeat_sequence"),
                latitude == null ? null : latitude.doubleValue(), longitude == null ? null : longitude.doubleValue(),
                lastPosition == null ? null : lastPosition.toInstant(), rs.getInt("capacity_limit"),
                rs.getInt("committed_count"), rs.getInt("reserved_count"), rs.getLong("capacity_version"),
                rs.getTimestamp("capacity_updated_at").toInstant());
    }

    private static DispatchOffer mapOffer(ResultSet rs, int row) throws SQLException {
        return new DispatchOffer(uuid(rs.getBytes("id")), uuid(rs.getBytes("order_id")),
                uuid(rs.getBytes("courier_id")), rs.getLong("courier_presence_generation"), rs.getInt("round_no"),
                rs.getString("token_hash"), rs.getString("token_ciphertext"),
                DispatchOfferStatus.valueOf(rs.getString("status")), rs.getTimestamp("expires_at").toInstant(),
                rs.getLong("order_version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), uuidNullable(rs.getBytes("policy_id")),
                rs.getObject("policy_version") == null ? 0 : rs.getLong("policy_version"),
                rs.getString("policy_snapshot"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID uuidNullable(byte[] bytes) {
        return bytes == null ? null : uuid(bytes);
    }

    private record CapacityRow(int limit, int committed, int reserved, long version) {
    }

    private record Slot(String phase, UUID offerId, UUID courierId) {
    }

    private record OfferIdentity(UUID orderId, UUID courierId) {
    }

    private record StaleOfferRef(UUID offerId, UUID orderId) {
    }
}
