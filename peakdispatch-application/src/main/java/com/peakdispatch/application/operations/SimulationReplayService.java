package com.peakdispatch.application.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.dispatch.DispatchPolicyProvider;
import com.peakdispatch.application.dispatch.DispatchPolicyService;
import com.peakdispatch.domain.dispatch.DispatchPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulationReplayService {
    private static final Instant SIMULATION_EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private final SimulationReplayRepository repository;
    private final DispatchPolicyService policies;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SimulationReplayService(SimulationReplayRepository repository, DispatchPolicyService policies,
                                   ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.policies = policies;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ReplayView replay(ReplayRequest request, String actorId) {
        validate(request);
        DispatchPolicyProvider.PolicySelection policy = request.policyVersion() == null
                ? policies.active(request.regionId())
                : policies.findByVersion(request.regionId(), request.policyVersion());
        ReplayReport report = simulate(request, policy);
        String resultJson = write(report);
        String hash = sha256(resultJson);
        ReplayView replay = new ReplayView(UUID.randomUUID(), request.regionId(), request.scenarioId(), request.seed(),
                policy.id(), policy.version(), request.orderCount(), request.courierCount(), hash, report);
        repository.save(replay, policy.configJson(), resultJson, actorId, Instant.now(clock));
        return replay;
    }

    @Transactional
    public Optional<StoredReplay> find(UUID replayId) {
        return repository.find(replayId);
    }

    @Transactional
    public void auditRead(String actorId, StoredReplay replay, String decision, String traceId) {
        repository.audit(actorId, "OPS_REPLAY_READ", replay.regionId(), replay.replayId().toString(), decision,
                "resultHash=" + replay.resultHash(), traceId, Instant.now(clock));
    }

    @Transactional
    public ReplayComparison compare(UUID firstId, UUID secondId, String actorId, String traceId) {
        StoredReplay first = repository.find(firstId).orElseThrow(() -> new IllegalArgumentException("replay not found"));
        StoredReplay second = repository.find(secondId).orElseThrow(() -> new IllegalArgumentException("replay not found"));
        if (!first.regionId().equals(second.regionId())) {
            repository.audit(actorId, "OPS_REPLAY_COMPARE", first.regionId(), firstId + "," + secondId,
                    "REJECTED", "reason=DIFFERENT_REGIONS;secondRegion=" + second.regionId(), traceId,
                    Instant.now(clock));
            throw new IllegalArgumentException("replays from different regions cannot be compared");
        }
        ReplayComparison comparison = new ReplayComparison(firstId, secondId,
                first.resultHash().equals(second.resultHash()), first.report().assignedOrders(),
                second.report().assignedOrders(), second.report().assignedOrders() - first.report().assignedOrders(),
                first.report().failedOrders(), second.report().failedOrders(),
                second.report().averageEtaSeconds() - first.report().averageEtaSeconds());
        repository.audit(actorId, "OPS_REPLAY_COMPARE", first.regionId(), firstId + "," + secondId,
                "ALLOWED", "identical=" + comparison.identical(), traceId, Instant.now(clock));
        return comparison;
    }

    private ReplayReport simulate(ReplayRequest request, DispatchPolicyProvider.PolicySelection selection) {
        Random random = new Random(request.seed() ^ request.scenarioId().hashCode());
        List<SimCourier> couriers = new ArrayList<>();
        for (int i = 0; i < request.courierCount(); i++) {
            couriers.add(new SimCourier(stableId(request.seed(), "courier", i),
                    random.nextDouble() * 0.12 - 0.06, random.nextDouble() * 0.12 - 0.06,
                    random.nextInt(3), 3, random.nextDouble() >= 0.08));
        }
        int assigned = 0;
        int failed = 0;
        double etaTotal = 0;
        List<OrderDecision> decisions = new ArrayList<>();
        for (int i = 0; i < request.orderCount(); i++) {
            double pickupLat = random.nextDouble() * 0.025 - 0.0125;
            double pickupLon = random.nextDouble() * 0.025 - 0.0125;
            double dropLat = pickupLat + random.nextDouble() * 0.035 - 0.0175;
            double dropLon = pickupLon + random.nextDouble() * 0.035 - 0.0175;
            Instant now = SIMULATION_EPOCH.plusSeconds(i * 30L);
            Instant prepEta = now.plusSeconds(180 + random.nextInt(600));
            Instant deadline = now.plusSeconds(1800 + random.nextInt(2700));
            List<ScoredCourier> candidates = couriers.stream().filter(SimCourier::online)
                    .filter(courier -> courier.committed() < courier.capacity())
                    .map(courier -> score(courier, pickupLat, pickupLon, dropLat, dropLon, prepEta, deadline,
                            now, selection.policy()))
                    .filter(candidate -> candidate.distanceKm() <= selection.policy().maxPickupDistanceKm()
                            && !candidate.deliveryAt().isAfter(deadline))
                    .sorted(Comparator.comparingDouble(ScoredCourier::score)
                            .thenComparing(ScoredCourier::deliveryAt)
                            .thenComparing(candidate -> candidate.courier().id().toString()))
                    .toList();
            UUID orderId = stableId(request.seed(), "order", i);
            if (candidates.isEmpty()) {
                failed++;
                decisions.add(new OrderDecision(orderId, "NO_CANDIDATE", null, null, null));
            } else {
                ScoredCourier selected = candidates.get(0);
                selected.courier().commit();
                assigned++;
                long etaSeconds = Math.max(0, selected.deliveryAt().getEpochSecond() - now.getEpochSecond());
                etaTotal += etaSeconds;
                decisions.add(new OrderDecision(orderId, "OFFER_CREATED", selected.courier().id(),
                        selected.score(), etaSeconds));
            }
        }
        return new ReplayReport(request.regionId(), request.scenarioId(), request.seed(), request.orderCount(),
                request.courierCount(), selection.id(), selection.version(), assigned, failed,
                request.orderCount() == 0 ? 0 : (double) assigned / request.orderCount(),
                assigned == 0 ? 0 : etaTotal / assigned, decisions);
    }

    private static ScoredCourier score(SimCourier courier, double pickupLat, double pickupLon,
                                       double dropLat, double dropLon, Instant prepEta, Instant deadline,
                                       Instant now, DispatchPolicy policy) {
        double distance = distanceKm(courier.lat(), courier.lon(), pickupLat, pickupLon) * 1.3;
        long pickupSeconds = Math.round(distance / 20.0 * 3600.0);
        Instant arrivalAtMerchant = now.plusSeconds(pickupSeconds);
        Instant pickupAt = arrivalAtMerchant.isBefore(prepEta) ? prepEta : arrivalAtMerchant;
        Instant deliveryAt = pickupAt.plusSeconds(Math.round(distanceKm(pickupLat, pickupLon, dropLat, dropLon)
                * 1.3 / 20.0 * 3600.0) + 120);
        double etaNorm = Math.min(1.0, pickupSeconds / 3600.0);
        double waitNorm = Math.min(1.0, Math.max(0, prepEta.getEpochSecond()
                - arrivalAtMerchant.getEpochSecond()) / 1800.0);
        double totalSlack = Math.max(1.0, deadline.getEpochSecond() - now.getEpochSecond());
        double remainingSlack = Math.max(0, deadline.getEpochSecond() - deliveryAt.getEpochSecond());
        double urgencyNorm = 1.0 - Math.min(1.0, remainingSlack / totalSlack);
        double loadRatio = courier.committed() / (double) courier.capacity();
        double score = policy.etaWeight() * etaNorm + policy.loadWeight() * loadRatio
                + policy.urgencyWeight() * urgencyNorm + waitNorm * 0.05;
        return new ScoredCourier(courier, score, distance, deliveryAt);
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

    private String write(ReplayReport report) {
        try {
            return mapper.writeValueAsString(report);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize replay report", exception);
        }
    }

    private static UUID stableId(long seed, String type, int index) {
        return UUID.nameUUIDFromBytes((seed + ":" + type + ":" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validate(ReplayRequest request) {
        if (request == null || request.regionId() == null || !request.regionId().matches("[A-Za-z0-9_-]{1,64}")
                || request.scenarioId() == null || !request.scenarioId().matches("[A-Za-z0-9_-]{1,64}")
                || request.orderCount() < 1 || request.orderCount() > 500
                || request.courierCount() < 1 || request.courierCount() > 200) {
            throw new IllegalArgumentException("replay scenario or counts are outside supported bounds");
        }
    }

    public record ReplayRequest(String regionId, long seed, String scenarioId, int orderCount,
                                int courierCount, Long policyVersion) {
    }

    public record ReplayView(UUID replayId, String regionId, String scenarioId, long seed, UUID policyId,
                             long policyVersion, int orderCount, int courierCount, String resultHash,
                             ReplayReport report) {
    }

    public record ReplayReport(String regionId, String scenarioId, long seed, int orderCount, int courierCount,
                               UUID policyId, long policyVersion, long assignedOrders, long failedOrders,
                               double assignmentRate, double averageEtaSeconds, List<OrderDecision> decisions) {
    }

    public record OrderDecision(UUID orderId, String outcome, UUID courierId, Double score, Long etaSeconds) {
    }

    public record StoredReplay(UUID replayId, String regionId, String createdBy, Instant createdAt,
                               String resultHash, ReplayReport report) {
    }

    public record ReplayComparison(UUID firstReplayId, UUID secondReplayId, boolean identical,
                                   long firstAssignedOrders, long secondAssignedOrders,
                                   long assignedDelta, long firstFailedOrders, long secondFailedOrders,
                                   double averageEtaDeltaSeconds) {
    }

    private record ScoredCourier(SimCourier courier, double score, double distanceKm, Instant deliveryAt) {
    }

    private static final class SimCourier {
        private final UUID id;
        private final double lat;
        private final double lon;
        private int committed;
        private final int capacity;
        private final boolean online;

        SimCourier(UUID id, double lat, double lon, int committed, int capacity, boolean online) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.committed = committed;
            this.capacity = capacity;
            this.online = online;
        }

        UUID id() { return id; }
        double lat() { return lat; }
        double lon() { return lon; }
        int committed() { return committed; }
        int capacity() { return capacity; }
        boolean online() { return online; }
        void commit() { committed++; }
    }
}
