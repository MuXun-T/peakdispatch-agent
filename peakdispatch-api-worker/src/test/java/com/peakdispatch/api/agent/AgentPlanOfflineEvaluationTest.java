package com.peakdispatch.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.agent.AgentBusinessTools;
import com.peakdispatch.application.agent.AgentConversationService;
import com.peakdispatch.application.agent.AgentIntent;
import com.peakdispatch.application.agent.AgentPlan;
import com.peakdispatch.application.agent.AgentPrincipal;
import com.peakdispatch.application.agent.AgentRepository;
import com.peakdispatch.application.agent.AgentToolGateway;
import com.peakdispatch.application.agent.DeterministicMockAgent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Repeatable, offline quality baseline for the default mock plan provider. */
@ExtendWith(MockitoExtension.class)
class AgentPlanOfflineEvaluationTest {
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("eval-operator",
            Set.of("OPS_ADMIN"), Set.of("demo-region"));

    @Mock AgentRepository repository;
    @Mock AgentBusinessTools businessTools;

    @Test
    void representativeRequests_keepIntentToolSlotsAndClarificationDecisionsStable() {
        when(businessTools.orderRegion(any(UUID.class))).thenReturn("demo-region");
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
        DeterministicMockAgent provider = new DeterministicMockAgent();
        List<Case> cases = List.of(
                new Case("region metrics", "查看区域指标", Map.of("regionId", "demo-region"),
                        AgentIntent.REGION_METRICS, "get_region_metrics", false,
                        Map.of("regionId", "demo-region", "granularity", "hour")),
                new Case("order lookup", "查询订单 " + ORDER_ID, Map.of(),
                        AgentIntent.ORDER_LOOKUP, "get_order_timeline", false,
                        Map.of("orderId", ORDER_ID.toString(), "limit", 20)),
                new Case("diagnosis", "诊断订单 " + ORDER_ID, Map.of(),
                        AgentIntent.ORDER_DIAGNOSIS, "diagnose_order", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("dispatch explanation", "解释派单记录 " + ORDER_ID, Map.of(),
                        AgentIntent.DISPATCH_EXPLANATION, "explain_dispatch", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("dispatch simulation", "模拟派单 " + ORDER_ID, Map.of(),
                        AgentIntent.DISPATCH_SIMULATION, "simulate_dispatch", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("peak comparison", "比较高峰方案", Map.of("regionId", "demo-region",
                        "from", "2026-10-15T08:00:00Z", "to", "2026-10-15T10:00:00Z", "seed", 7L),
                        AgentIntent.PEAK_PLAN_COMPARISON, "compare_peak_plans", false,
                        Map.of("regionId", "demo-region", "from", "2026-10-15T08:00:00Z",
                                "to", "2026-10-15T10:00:00Z", "seed", 7L)),
                new Case("sop", "查看高峰操作规范", Map.of("regionId", "demo-region"),
                        AgentIntent.SOP_QA, "search_sop", false,
                        Map.of("regionId", "demo-region", "category", "SOP")),
                new Case("cancel", "取消订单 " + ORDER_ID, Map.of("expectedVersion", 3L,
                        "reasonCode", "CUSTOMER_REQUEST"), AgentIntent.CANCEL_ORDER, "request_cancel", false,
                        Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 3L,
                                "reasonCode", "CUSTOMER_REQUEST")),
                new Case("reschedule", "调整时间 " + ORDER_ID, Map.of("expectedVersion", 3L,
                        "reasonCode", "CUSTOMER_REQUEST", "prepEta", "2026-10-15T10:00:00Z",
                        "deadline", "2026-10-15T11:00:00Z"), AgentIntent.RESCHEDULE, "request_reschedule", false,
                        Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 3L,
                                "reasonCode", "CUSTOMER_REQUEST", "prepEta", "2026-10-15T10:00:00Z",
                                "deadline", "2026-10-15T11:00:00Z")),
                new Case("reassign", "请改派订单 " + ORDER_ID, Map.of("expectedVersion", 3L,
                        "reasonCode", "COURIER_REQUEST"), AgentIntent.REASSIGN_ORDER,
                        "request_reassignment", false, Map.of("orderId", ORDER_ID.toString(),
                                "expectedVersion", 3L, "reasonCode", "COURIER_REQUEST")),
                new Case("missing region", "查看区域指标", Map.of(), AgentIntent.REGION_METRICS,
                        null, true, Map.of()),
                new Case("ambiguous", "查看区域指标并诊断订单 " + ORDER_ID, Map.of("regionId", "demo-region"),
                        AgentIntent.UNKNOWN, null, true, Map.of()),
                new Case("missing approval slots", "取消订单 " + ORDER_ID, Map.of(), AgentIntent.CANCEL_ORDER,
                        null, true, Map.of()),
                new Case("region metrics day", "查看区域指标", Map.of("regionId", "demo-region",
                        "granularity", "day"), AgentIntent.REGION_METRICS, "get_region_metrics", false,
                        Map.of("regionId", "demo-region", "granularity", "day")),
                new Case("region metrics window", "dashboard metrics", Map.of("regionId", "demo-region",
                        "from", "2026-10-15T08:00:00Z", "to", "2026-10-15T09:00:00Z"),
                        AgentIntent.REGION_METRICS, "get_region_metrics", false,
                        Map.of("regionId", "demo-region", "from", "2026-10-15T08:00:00Z",
                                "to", "2026-10-15T09:00:00Z", "granularity", "hour")),
                new Case("online metrics", "查看在线骑手和待派量", Map.of("regionId", "demo-region"),
                        AgentIntent.REGION_METRICS, "get_region_metrics", false,
                        Map.of("regionId", "demo-region", "granularity", "hour")),
                new Case("lookup by slot", "查询订单状态 " + ORDER_ID, Map.of("orderId", ORDER_ID.toString()),
                        AgentIntent.ORDER_LOOKUP, "get_order_timeline", false,
                        Map.of("orderId", ORDER_ID.toString(), "limit", 20)),
                new Case("lookup history", "查询订单时间线 " + ORDER_ID, Map.of(), AgentIntent.ORDER_LOOKUP,
                        "get_order_timeline", false, Map.of("orderId", ORDER_ID.toString(), "limit", 20)),
                new Case("diagnosis why", "为什么订单 " + ORDER_ID + " 没有派成", Map.of(),
                        AgentIntent.ORDER_DIAGNOSIS, "diagnose_order", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("diagnosis slot", "查看异常原因", Map.of("orderId", ORDER_ID.toString()),
                        AgentIntent.ORDER_DIAGNOSIS, "diagnose_order", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("dispatch status", "查看订单派单情况 " + ORDER_ID, Map.of(),
                        AgentIntent.DISPATCH_EXPLANATION, "explain_dispatch", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("dispatch history", "dispatch history", Map.of("orderId", ORDER_ID.toString()),
                        AgentIntent.DISPATCH_EXPLANATION, "explain_dispatch", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("simulation history", "模拟派单历史", Map.of("orderId", ORDER_ID.toString()),
                        AgentIntent.DISPATCH_SIMULATION, "simulate_dispatch", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("simulation english", "simulate dispatch", Map.of("orderId", ORDER_ID.toString()),
                        AgentIntent.DISPATCH_SIMULATION, "simulate_dispatch", false,
                        Map.of("orderId", ORDER_ID.toString())),
                new Case("peak default", "高峰调度摘要", Map.of("regionId", "demo-region"),
                        AgentIntent.PEAK_PLAN_COMPARISON, "compare_peak_plans", false,
                        Map.of("regionId", "demo-region", "seed", 0L)),
                new Case("peak window", "比较高峰方案", Map.of("regionId", "demo-region",
                        "from", "2026-10-15T08:00:00Z", "to", "2026-10-15T10:00:00Z"),
                        AgentIntent.PEAK_PLAN_COMPARISON, "compare_peak_plans", false,
                        Map.of("regionId", "demo-region", "from", "2026-10-15T08:00:00Z",
                                "to", "2026-10-15T10:00:00Z", "seed", 0L)),
                new Case("peak alternate seed", "peak plans", Map.of("regionId", "demo-region", "seed", 42L),
                        AgentIntent.PEAK_PLAN_COMPARISON, "compare_peak_plans", false,
                        Map.of("regionId", "demo-region", "seed", 42L)),
                new Case("sop policy", "policy for operations", Map.of("regionId", "demo-region"),
                        AgentIntent.SOP_QA, "search_sop", false,
                        Map.of("regionId", "demo-region", "category", "SOP")),
                new Case("sop category", "如何处理骑手拒单", Map.of("regionId", "demo-region",
                        "category", "Dispatch"), AgentIntent.SOP_QA, "search_sop", false,
                        Map.of("regionId", "demo-region", "category", "Dispatch")),
                new Case("cancel slot", "cancel order", Map.of("orderId", ORDER_ID.toString(),
                        "expectedVersion", 4L, "reasonCode", "OPS_REQUEST"), AgentIntent.CANCEL_ORDER,
                        "request_cancel", false, Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 4L,
                                "reasonCode", "OPS_REQUEST")),
                new Case("cancel customer", "客户要求取消订单", Map.of("orderId", ORDER_ID.toString(),
                        "expectedVersion", 5L, "reasonCode", "CUSTOMER_REQUEST"), AgentIntent.CANCEL_ORDER,
                        "request_cancel", false, Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 5L,
                                "reasonCode", "CUSTOMER_REQUEST")),
                new Case("reschedule slot", "修改送达时间", Map.of("orderId", ORDER_ID.toString(),
                        "expectedVersion", 4L, "reasonCode", "OPS_REQUEST", "prepEta", "2026-10-15T12:00:00Z",
                        "deadline", "2026-10-15T13:00:00Z"), AgentIntent.RESCHEDULE, "request_reschedule", false,
                        Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 4L, "reasonCode", "OPS_REQUEST",
                                "prepEta", "2026-10-15T12:00:00Z", "deadline", "2026-10-15T13:00:00Z")),
                new Case("reschedule merchant", "修改出餐时间", Map.of("orderId", ORDER_ID.toString(),
                        "expectedVersion", 5L, "reasonCode", "MERCHANT_DELAY", "prepEta", "2026-10-15T14:00:00Z",
                        "deadline", "2026-10-15T15:00:00Z"), AgentIntent.RESCHEDULE, "request_reschedule", false,
                        Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 5L,
                                "reasonCode", "MERCHANT_DELAY", "prepEta", "2026-10-15T14:00:00Z",
                                "deadline", "2026-10-15T15:00:00Z")),
                new Case("reassign slot", "换骑手", Map.of("orderId", ORDER_ID.toString(),
                        "expectedVersion", 4L, "reasonCode", "OPS_REQUEST"), AgentIntent.REASSIGN_ORDER,
                        "request_reassignment", false, Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 4L,
                                "reasonCode", "OPS_REQUEST")),
                new Case("reassign english", "reassign", Map.of("orderId", ORDER_ID.toString(),
                        "expectedVersion", 5L, "reasonCode", "COURIER_REQUEST"), AgentIntent.REASSIGN_ORDER,
                        "request_reassignment", false, Map.of("orderId", ORDER_ID.toString(), "expectedVersion", 5L,
                                "reasonCode", "COURIER_REQUEST")),
                new Case("missing order", "诊断订单异常", Map.of(), AgentIntent.ORDER_DIAGNOSIS,
                        null, true, Map.of()),
                new Case("missing action version", "取消订单 " + ORDER_ID,
                        Map.of("reasonCode", "CUSTOMER_REQUEST"), AgentIntent.CANCEL_ORDER, null, true, Map.of()),
                new Case("missing reschedule deadline", "调整时间 " + ORDER_ID,
                        Map.of("expectedVersion", 3L, "reasonCode", "OPS_REQUEST", "prepEta", "2026-10-15T10:00:00Z"),
                        AgentIntent.RESCHEDULE, null, true, Map.of()),
                new Case("unknown request", "请帮我处理一下", Map.of(), AgentIntent.UNKNOWN, null, true, Map.of()));

        int clarified = 0;
        int executable = 0;
        for (Case testCase : cases) {
            AgentPlan plan = provider.plan(testCase.message(), testCase.slots());
            assertThat(plan.intent()).as(testCase.name()).isEqualTo(testCase.intent());
            assertThat(plan.needsClarification()).as(testCase.name()).isEqualTo(testCase.clarification());
            assertThat(plan.toolName()).as(testCase.name()).isEqualTo(testCase.toolName());
            if (testCase.clarification()) {
                assertThat(plan.arguments()).as(testCase.name()).isEmpty();
                clarified++;
            } else {
                executable++;
                Map<String, Object> expectedArguments = new java.util.LinkedHashMap<>(testCase.expectedSlots());
                if ("search_sop".equals(testCase.toolName())) {
                    expectedArguments.put("query", testCase.message());
                }
                assertThat(plan.arguments()).as(testCase.name()).isEqualTo(expectedArguments);
                Map<String, Object> validated = gateway.validatePlan(UUID.randomUUID(), plan.intent(),
                        plan.toolName(), plan.arguments(), PRINCIPAL, "offline-evaluation");
                Map<String, Object> expectedValidated = new java.util.LinkedHashMap<>(expectedArguments);
                if ("get_region_metrics".equals(testCase.toolName())
                        || "compare_peak_plans".equals(testCase.toolName())) {
                    for (String key : List.of("from", "to")) {
                        if (expectedValidated.containsKey(key)) {
                            expectedValidated.put(key, Instant.parse((String) expectedValidated.get(key)));
                        }
                    }
                }
                if ("simulate_dispatch".equals(testCase.toolName())) {
                    Object orderId = expectedValidated.remove("orderId");
                    if (orderId != null) {
                        expectedValidated.put("regionId", "demo-region");
                        expectedValidated.put("orderIds", List.of(UUID.fromString((String) orderId)));
                    }
                }
                assertThat(validated).as(testCase.name()).isEqualTo(expectedValidated);
            }
        }
        assertThat(clarified).isEqualTo(7);
        assertThat(executable).isEqualTo(32);
        assertThat(cases).hasSize(39);
        assertThat(cases.size()).isGreaterThanOrEqualTo(30);
    }

    private record Case(String name, String message, Map<String, Object> slots, AgentIntent intent,
                        String toolName, boolean clarification, Map<String, Object> expectedSlots) {
    }
}
