package com.peakdispatch.application.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Produces deterministic aggregate capacity scenarios; it does not dispatch orders. */
@Service
public class PeakPlanAnalysisService {
    public static final String RULE_VERSION = "peak-capacity-scenarios-v1";
    private static final List<String> LIMITATIONS = List.of(
            "方案仅为基于区域汇总指标的容量情景比较，不会创建 Offer 或改变订单状态。",
            "当前输入没有逐单路线和实时道路数据，结果不代表逐单 SLA 保证或全局最优。",
            "相同指标、规则版本和 seed 会生成相同的方案内容；seed 不改变汇总容量约束。"
    );

    private final OperationsService operations;
    private final Clock clock;

    public PeakPlanAnalysisService(OperationsService operations, Clock clock) {
        this.operations = operations;
        this.clock = clock;
    }

    public Comparison compare(String regionId, Instant from, Instant to, long seed,
                              String actorId, String traceId) {
        Instant asOf = Instant.now(clock);
        Instant effectiveTo = to == null ? asOf : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(Duration.ofHours(4)) : from;
        OperationsRepository.DashboardSnapshot dashboard = operations.dashboard(regionId,
                effectiveFrom, effectiveTo, actorId, traceId);
        Map<String, Long> metrics = Map.of(
                "orders", dashboard.orders(),
                "waitingDispatch", dashboard.waitingDispatch(),
                "offered", dashboard.offered(),
                "accepted", dashboard.accepted(),
                "offerTimedOut", dashboard.offerTimedOut(),
                "dispatchFailures", dashboard.dispatchFailures(),
                "escalations", dashboard.escalations(),
                "onlineCouriers", dashboard.onlineCouriers(),
                "availableCapacity", dashboard.availableCapacity());
        int queue = Math.toIntExact(Math.min(Integer.MAX_VALUE, dashboard.waitingDispatch()));
        int capacity = Math.toIntExact(Math.min(Integer.MAX_VALUE, dashboard.availableCapacity()));
        int feasible = Math.min(queue, capacity);
        Map<String, Object> constraints = Map.of(
                "maxOrdersPerCourier", 3,
                "capacityUnitPerOrder", 1,
                "availableCapacityUnits", capacity,
                "waitingOrders", queue,
                "plannedOrdersMustNotExceedCapacity", true,
                "mutatesDispatch", false);
        List<Plan> plans = List.of(
                plan("CONSERVATIVE", "capacity-first-at-50-percent", queue, capacity,
                        Math.min(feasible, capacity / 2), seed, metrics, constraints),
                plan("BALANCED", "capacity-first-at-75-percent", queue, capacity,
                        Math.min(feasible, (capacity * 3 + 3) / 4), seed, metrics, constraints),
                plan("THROUGHPUT", "use-all-current-capacity", queue, capacity,
                        feasible, seed, metrics, constraints));
        return new Comparison(RULE_VERSION, regionId, effectiveFrom, effectiveTo, dashboard.asOf(),
                "mysql_operations_dashboard", seed, metrics, constraints, plans, LIMITATIONS);
    }

    private static Plan plan(String id, String rule, int queue, int capacity, int assignments, long seed,
                             Map<String, Long> metrics, Map<String, Object> constraints) {
        int remaining = Math.max(0, queue - assignments);
        int utilization = capacity == 0 ? 0 : (int) Math.round(assignments * 100.0 / capacity);
        return new Plan(id, rule, RULE_VERSION, seed, metrics, constraints,
                new PredictedOutcome(assignments, remaining, assignments, capacity,
                        Math.min(100, utilization)), LIMITATIONS);
    }

    public record Comparison(String rulesVersion, String regionId, Instant from, Instant to, Instant asOf,
                             String source, long seed, Map<String, Long> inputMetrics,
                             Map<String, Object> constraints, List<Plan> plans, List<String> limitations) {
    }

    public record Plan(String id, String rule, String rulesVersion, long seed,
                       Map<String, Long> inputMetrics, Map<String, Object> constraints,
                       PredictedOutcome predictedOutcome, List<String> limitations) {
    }

    public record PredictedOutcome(int ordersPlannedThisCycle, int ordersRemaining,
                                   int capacityUnitsUsed, int capacityUnitsAvailable,
                                   int projectedCapacityUtilizationPercent) {
    }
}
