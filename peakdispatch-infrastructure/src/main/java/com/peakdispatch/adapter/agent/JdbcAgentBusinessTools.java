package com.peakdispatch.adapter.agent;

import com.peakdispatch.application.agent.AgentBusinessTools;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.peakdispatch.application.dispatch.BundleRoutePreviewService;
import com.peakdispatch.application.knowledge.KnowledgeService;
import com.peakdispatch.application.operations.OperationsRepository;
import com.peakdispatch.application.operations.PeakPlanAnalysisService;
import com.peakdispatch.application.operations.OperationsService;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.domain.order.DeliveryOrder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;

@Component
public class JdbcAgentBusinessTools implements AgentBusinessTools {
    private final OperationsService operations;
    private final PeakPlanAnalysisService peakPlans;
    private final OrderApplicationService orders;
    private final KnowledgeService knowledge;
    private final BundleRoutePreviewService bundlePreviews;
    private final Clock clock;

    public JdbcAgentBusinessTools(OperationsService operations, PeakPlanAnalysisService peakPlans,
                                  OrderApplicationService orders,
                                  KnowledgeService knowledge, BundleRoutePreviewService bundlePreviews,
                                  Clock clock) {
        this.operations = operations;
        this.peakPlans = peakPlans;
        this.orders = orders;
        this.knowledge = knowledge;
        this.bundlePreviews = bundlePreviews;
        this.clock = clock;
    }

    @Override
    public String orderRegion(UUID orderId) {
        return operations.orderRegion(orderId);
    }

    @Override
    public String courierRegion(UUID courierId) {
        return operations.courierRegion(courierId);
    }

    @Override
    public Map<String, Object> invoke(String toolName, Map<String, Object> args,
                                      String actorId, String traceId) {
        return switch (toolName) {
            case "get_region_metrics" -> regionMetrics(args, actorId, traceId);
            case "compare_peak_plans" -> comparePeakPlans(args, actorId, traceId);
            case "get_order_timeline" -> orderTimeline(args, actorId, traceId);
            case "diagnose_order" -> diagnose(args, actorId, traceId);
            case "explain_dispatch" -> explainDispatch(args, actorId, traceId);
            case "simulate_dispatch" -> simulateRoutes(args, actorId, traceId);
            case "search_sop" -> searchSop(args, actorId, traceId);
            default -> throw new IllegalArgumentException("Agent tool is not implemented");
        };
    }

    private Map<String, Object> regionMetrics(Map<String, Object> args, String actorId, String traceId) {
        Instant now = Instant.now(clock);
        Instant from = args.get("from") instanceof Instant value ? value : now.minus(Duration.ofHours(24));
        Instant to = args.get("to") instanceof Instant value ? value : now;
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        OperationsRepository.DashboardSnapshot dashboard = operations.dashboard((String) args.get("regionId"),
                from, to, actorId, traceId);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("orders", dashboard.orders());
        metrics.put("waitingDispatch", dashboard.waitingDispatch());
        metrics.put("offered", dashboard.offered());
        metrics.put("accepted", dashboard.accepted());
        metrics.put("offerTimedOut", dashboard.offerTimedOut());
        metrics.put("dispatchFailures", dashboard.dispatchFailures());
        metrics.put("escalations", dashboard.escalations());
        metrics.put("onlineCouriers", dashboard.onlineCouriers());
        metrics.put("availableCapacity", dashboard.availableCapacity());
        return Map.of("regionId", dashboard.regionId(), "from", from.toString(), "to", to.toString(),
                "asOf", dashboard.asOf().toString(), "source", "operations_dashboard",
                "definition", "订单与派单计数按创建/事件时间窗口统计；在线骑手与运力为查询时快照",
                "granularity", args.get("granularity"), "metrics", metrics);
    }

    private Map<String, Object> comparePeakPlans(Map<String, Object> args, String actorId, String traceId) {
        Instant from = args.get("from") instanceof Instant value ? value : null;
        Instant to = args.get("to") instanceof Instant value ? value : null;
        long seed = ((Number) args.getOrDefault("seed", 0L)).longValue();
        PeakPlanAnalysisService.Comparison comparison = peakPlans.compare((String) args.get("regionId"),
                from, to, seed, actorId, traceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rulesVersion", comparison.rulesVersion());
        result.put("regionId", comparison.regionId());
        result.put("from", comparison.from().toString());
        result.put("to", comparison.to().toString());
        result.put("asOf", comparison.asOf().toString());
        result.put("source", comparison.source());
        result.put("seed", comparison.seed());
        result.put("inputMetrics", comparison.inputMetrics());
        result.put("constraints", comparison.constraints());
        result.put("plans", comparison.plans());
        result.put("limitations", comparison.limitations());
        return Map.copyOf(result);
    }

    private Map<String, Object> orderTimeline(Map<String, Object> args, String actorId, String traceId) {
        UUID orderId = UUID.fromString((String) args.get("orderId"));
        DeliveryOrder order = orders.get(orderId);
        List<OperationsRepository.TimelineEntry> timeline = operations.orderTimeline(orderId, actorId, traceId);
        int limit = ((Number) args.getOrDefault("limit", 20)).intValue();
        List<Map<String, Object>> events = timeline.stream().skip(Math.max(0, timeline.size() - limit))
                .map(JdbcAgentBusinessTools::eventMap).toList();
        return Map.of("orderId", orderId.toString(), "regionId", order.regionId(),
                "status", order.status().name(), "version", order.version(),
                "asOf", Instant.now(clock).toString(), "events", events);
    }

    private Map<String, Object> diagnose(Map<String, Object> args, String actorId, String traceId) {
        Map<String, Object> timeline = orderTimeline(Map.of("orderId", args.get("orderId"), "limit", 50),
                actorId, traceId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) timeline.get("events");
        UUID orderId = UUID.fromString((String) args.get("orderId"));
        DeliveryOrder order = orders.get(orderId);
        List<OperationsRepository.DispatchAttemptEvidence> attempts = operations.dispatchEvidence(orderId,
                actorId, traceId);
        List<Map<String, Object>> dispatchEvidence = attempts.stream()
                .map(JdbcAgentBusinessTools::dispatchEvidenceMap).toList();
        List<Map<String, Object>> evidence = new ArrayList<>(events);
        evidence.addAll(dispatchEvidence);
        List<String> reasons = new ArrayList<>();
        Map<String, List<String>> reasonEvidence = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String type = String.valueOf(event.get("type"));
            String evidenceId = String.valueOf(event.get("evidenceId"));
            if (type.contains("NO_CANDIDATE")) {
                bindReason(reasons, reasonEvidence, "NO_ELIGIBLE_COURIER_RECORDED", evidenceId);
            }
            if (type.contains("EXHAUSTED") || type.contains("ESCALAT")) {
                bindReason(reasons, reasonEvidence, "DISPATCH_RETRY_LIMIT_REACHED", evidenceId);
            }
            if (type.contains("REJECTED")) bindReason(reasons, reasonEvidence, "OFFER_REJECTED", evidenceId);
            if (type.contains("EXPIRED") || type.contains("TIMEOUT")) {
                bindReason(reasons, reasonEvidence, "OFFER_TIMED_OUT", evidenceId);
            }
        }
        for (OperationsRepository.DispatchAttemptEvidence attempt : attempts) {
            String outcome = attempt.outcome() == null ? "" : attempt.outcome().toUpperCase();
            if (outcome.contains("NO_CANDIDATE")) {
                bindReason(reasons, reasonEvidence, "NO_ELIGIBLE_COURIER_RECORDED", attempt.evidenceId());
            }
            if (outcome.contains("EXHAUSTED") || outcome.contains("ESCALAT")) {
                bindReason(reasons, reasonEvidence, "DISPATCH_RETRY_LIMIT_REACHED", attempt.evidenceId());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", timeline.get("orderId"));
        result.put("regionId", timeline.get("regionId"));
        result.put("status", timeline.get("status"));
        result.put("version", timeline.get("version"));
        result.put("asOf", timeline.get("asOf"));
        result.put("prepEta", order.prepEta().toString());
        result.put("deadline", order.deadline().toString());
        result.put("dispatchRounds", dispatchEvidence);
        result.put("reasonCodes", List.copyOf(reasons));
        result.put("reasonEvidence", reasonEvidence);
        result.put("evidence", evidence);
        result.put("unknowns", reasons.isEmpty()
                ? List.of("持久化时间线中没有可确认的失败原因") : List.of());
        return result;
    }

    private static void bindReason(List<String> reasons, Map<String, List<String>> reasonEvidence,
                                   String reason, String evidenceId) {
        if (!reasons.contains(reason)) reasons.add(reason);
        if (evidenceId == null || evidenceId.isBlank() || "null".equals(evidenceId)) return;
        List<String> bound = reasonEvidence.computeIfAbsent(reason, ignored -> new ArrayList<>());
        if (!bound.contains(evidenceId)) bound.add(evidenceId);
    }

    private static Map<String, Object> dispatchEvidenceMap(OperationsRepository.DispatchAttemptEvidence attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidenceId", attempt.evidenceId());
        result.put("attemptId", attempt.attemptId().toString());
        result.put("roundNo", attempt.roundNo());
        result.put("orderVersion", attempt.orderVersion());
        result.put("outcome", attempt.outcome());
        result.put("candidateCount", attempt.candidateCount());
        result.put("selectedCourierId", attempt.selectedCourierId() == null
                ? null : attempt.selectedCourierId().toString());
        result.put("policyVersion", attempt.policyVersion());
        result.put("exclusionSummary", redact(attempt.exclusionSummary()));
        result.put("startedAt", attempt.startedAt().toString());
        result.put("finishedAt", attempt.finishedAt() == null ? null : attempt.finishedAt().toString());
        result.put("candidates", attempt.candidates().stream().map(candidate -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("evidenceId", candidate.evidenceId());
            item.put("courierId", candidate.courierId().toString());
            item.put("eligible", candidate.eligible());
            item.put("reasonCode", candidate.reasonCode());
            item.put("rankNo", candidate.rankNo());
            item.put("score", candidate.score());
            item.put("featureSnapshot", redact(candidate.featureSnapshot()));
            return item;
        }).toList());
        return result;
    }

    private Map<String, Object> explainDispatch(Map<String, Object> args, String actorId, String traceId) {
        Map<String, Object> timeline = orderTimeline(Map.of("orderId", args.get("orderId"), "limit", 50),
                actorId, traceId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) timeline.get("events");
        List<Map<String, Object>> dispatch = events.stream()
                .filter(event -> "DISPATCH_ATTEMPT".equals(event.get("source"))
                        || "DISPATCH_OFFER".equals(event.get("source"))).toList();
        return Map.of("orderId", timeline.get("orderId"), "status", timeline.get("status"),
                "version", timeline.get("version"), "asOf", timeline.get("asOf"),
                "dispatchEvidence", dispatch,
                "explanation", dispatch.isEmpty()
                        ? "没有持久化的派单尝试或 Offer 记录，不能推断未记录的候选原因。"
                        : "以下派单尝试与 Offer 状态来自订单时间线，可用于解释当前结果。",
                "source", "mysql_dispatch_timeline");
    }

    private Map<String, Object> simulateRoutes(Map<String, Object> args, String actorId, String traceId) {
        @SuppressWarnings("unchecked")
        List<UUID> orderIds = (List<UUID>) args.get("orderIds");
        String regionId = (String) args.get("regionId");
        List<UUID> courierIds;
        boolean candidatePoolTruncated = false;
        if (args.get("courierId") instanceof UUID courierId) {
            courierIds = List.of(courierId);
        } else {
            OperationsRepository.CourierPage page = operations.searchCouriers(regionId, null, null,
                    null, 0, 50, actorId, traceId);
            courierIds = page.items().stream().map(OperationsRepository.CourierRow::courierId).toList();
            candidatePoolTruncated = page.totalElements() > courierIds.size();
        }

        List<BundleRoutePreviewService.Preview> previews = new ArrayList<>();
        for (UUID courierId : courierIds) {
            try {
                previews.add(bundlePreviews.preview(courierId, orderIds));
            } catch (RuntimeException ignored) {
                // A courier can change or disappear between the regional query and the preview.
            }
        }
        previews.sort(Comparator.comparing(BundleRoutePreviewService.Preview::executable).reversed()
                .thenComparingInt(preview -> preview.failureReasons().size())
                .thenComparing(preview -> preview.estimatedElapsed().toSeconds())
                .thenComparing(preview -> preview.courierId().toString()));

        List<Map<String, Object>> candidates = previews.stream().limit(10)
                .map(JdbcAgentBusinessTools::routeCandidateMap).toList();
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        previews.forEach(preview -> constraints.addAll(preview.failureReasons()));
        if (previews.isEmpty()) constraints.add("NO_COURIER_CANDIDATES");
        Map<String, Long> inputVersions = new LinkedHashMap<>();
        if (!previews.isEmpty()) {
            previews.getFirst().candidateOrders().forEach(order -> inputVersions.put(order.orderId().toString(), order.version()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulationId", UUID.randomUUID().toString());
        result.put("ruleVersion", BundleRoutePreviewService.RULE_VERSION);
        result.put("regionId", regionId);
        result.put("orderIds", orderIds.stream().map(UUID::toString).toList());
        result.put("asOf", Instant.now(clock).toString());
        result.put("source", "mysql_primary");
        result.put("inputVersions", inputVersions);
        result.put("candidateCount", previews.size());
        result.put("candidatePoolTruncated", candidatePoolTruncated);
        result.put("candidates", candidates);
        result.put("constraints", List.copyOf(constraints));
        result.put("mutatesDispatch", false);
        result.put("limitation", "Read-only route preview; no offer or capacity reservation is created.");
        return result;
    }

    private static Map<String, Object> routeCandidateMap(BundleRoutePreviewService.Preview preview) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("courierId", preview.courierId().toString());
        candidate.put("executable", preview.executable());
        candidate.put("failureReasons", preview.failureReasons());
        candidate.put("routeOrder", preview.routeOrder());
        candidate.put("estimatedTimes", preview.estimatedTimes());
        candidate.put("estimatedDistanceKm", preview.estimatedDistanceKm());
        candidate.put("estimatedElapsedSeconds", preview.estimatedElapsed().toSeconds());
        candidate.put("capacity", preview.capacity());
        return Map.copyOf(candidate);
    }

    private Map<String, Object> searchSop(Map<String, Object> args, String actorId, String traceId) {
        KnowledgeService.SearchResponse response = knowledge.search((String) args.get("query"),
                (String) args.get("regionId"), (String) args.get("category"), 5, actorId, traceId);
        List<Map<String, Object>> citations = response.citations().stream().map(citation -> Map.<String, Object>of(
                "citationId", citation.citationId(), "title", citation.title(), "version", citation.version(),
                "section", citation.section(), "uri", citation.uri(), "excerpt", citation.excerpt(),
                "score", citation.score())).toList();
        return Map.of("status", response.status(), "errorCode", response.errorCode() == null ? "" : response.errorCode(),
                "citations", citations, "asOf", Instant.now(clock).toString());
    }

    private static Map<String, Object> eventMap(OperationsRepository.TimelineEntry event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("occurredAt", event.occurredAt().toString());
        result.put("type", event.type());
        result.put("source", event.source());
        result.put("version", event.version());
        result.put("evidenceId", event.evidenceId());
        result.put("details", redact(event.details()));
        return result;
    }

    private static String redact(String details) {
        if (details == null) return "";
        return SensitiveDataRedactor.redact(details);
    }
}
