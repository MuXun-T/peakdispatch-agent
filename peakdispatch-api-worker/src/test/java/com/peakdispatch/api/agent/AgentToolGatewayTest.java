package com.peakdispatch.api.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.agent.AgentAccessDeniedException;
import com.peakdispatch.application.agent.AgentBusinessTools;
import com.peakdispatch.application.agent.AgentIntent;
import com.peakdispatch.application.agent.AgentPrincipal;
import com.peakdispatch.application.agent.AgentRepository;
import com.peakdispatch.application.agent.AgentToolGateway;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentToolGatewayTest {
    @Mock AgentRepository repository;
    @Mock AgentBusinessTools businessTools;

    @Test
    void invoke_withUnknownToolOrForgedIdentity_rejectsBeforeBusinessCall() {
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools,
                new ObjectMapper(), Clock.systemUTC());
        AgentPrincipal principal = new AgentPrincipal("viewer", Set.of("OPS_VIEWER"), Set.of("demo-region"));

        assertThatThrownBy(() -> gateway.invoke(UUID.randomUUID(), AgentIntent.REGION_METRICS, "primary",
                "http_get", Map.of("url", "https://example.invalid"), principal, "trace"))
                .isInstanceOf(AgentAccessDeniedException.class);
        assertThatThrownBy(() -> gateway.invoke(UUID.randomUUID(), AgentIntent.REGION_METRICS, "primary",
                "get_region_metrics", Map.of("regionId", "demo-region", "userId", "admin"),
                principal, "trace"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, org.mockito.Mockito.times(2)).audit(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(businessTools);
        verify(repository, never()).beginToolCall(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void validatePlan_rejectsWrongIntentToolAndMissingOrForgedArgumentsBeforeCall() {
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools,
                new ObjectMapper(), Clock.systemUTC());
        AgentPrincipal principal = new AgentPrincipal("viewer", Set.of("OPS_VIEWER"), Set.of("demo-region"));
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> gateway.validatePlan(runId, AgentIntent.REGION_METRICS,
                "diagnose_order", Map.of("orderId", UUID.randomUUID().toString()), principal, "trace"))
                .isInstanceOf(AgentAccessDeniedException.class);
        assertThat(gateway.missingRequiredArguments("request_cancel", Map.of("orderId", "order-id")))
                .contains("expectedVersion", "reasonCode");
        assertThatThrownBy(() -> gateway.validatePlan(runId, AgentIntent.REGION_METRICS,
                "get_region_metrics", Map.of("regionId", "demo-region", "actorId", "admin"), principal, "trace"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(businessTools, never()).invoke(anyString(), any(), anyString(), anyString());
        verify(repository, never()).beginToolCall(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void validatePlan_acceptsEachRegisteredIntentToolMappingWithValidArguments() {
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools,
                new ObjectMapper(), Clock.systemUTC());
        AgentPrincipal principal = new AgentPrincipal("admin", Set.of("OPS_ADMIN"), Set.of("demo-region"));
        UUID orderId = UUID.randomUUID();
        when(businessTools.orderRegion(orderId)).thenReturn("demo-region");
        List<PlanCase> plans = List.of(
                new PlanCase(AgentIntent.REGION_METRICS, "get_region_metrics",
                        Map.of("regionId", "demo-region", "granularity", "day")),
                new PlanCase(AgentIntent.PEAK_PLAN_COMPARISON, "compare_peak_plans",
                        Map.of("regionId", "demo-region", "seed", 7)),
                new PlanCase(AgentIntent.ORDER_LOOKUP, "get_order_timeline",
                        Map.of("orderId", orderId.toString(), "limit", 10)),
                new PlanCase(AgentIntent.ORDER_DIAGNOSIS, "diagnose_order", Map.of("orderId", orderId.toString())),
                new PlanCase(AgentIntent.DISPATCH_EXPLANATION, "explain_dispatch", Map.of("orderId", orderId.toString())),
                new PlanCase(AgentIntent.DISPATCH_SIMULATION, "simulate_dispatch", Map.of("orderId", orderId.toString())),
                new PlanCase(AgentIntent.SOP_QA, "search_sop",
                        Map.of("query", "dispatch delay", "regionId", "demo-region")),
                new PlanCase(AgentIntent.CANCEL_ORDER, "request_cancel",
                        Map.of("orderId", orderId.toString(), "expectedVersion", 3, "reasonCode", "CUSTOMER_REQUEST")),
                new PlanCase(AgentIntent.RESCHEDULE, "request_reschedule",
                        Map.of("orderId", orderId.toString(), "expectedVersion", 3,
                                "reasonCode", "CUSTOMER_REQUEST", "prepEta", "2026-01-01T10:00:00Z",
                                "deadline", "2026-01-01T11:00:00Z")),
                new PlanCase(AgentIntent.REASSIGN_ORDER, "request_reassignment",
                        Map.of("orderId", orderId.toString(), "expectedVersion", 3, "reasonCode", "COURIER_REQUEST")));

        for (PlanCase plan : plans) {
            assertThat(gateway.validatePlan(UUID.randomUUID(), plan.intent(), plan.toolName(), plan.arguments(),
                    principal, "trace")).isNotEmpty();
        }

        verifyNoInteractions(repository);
        verify(businessTools, org.mockito.Mockito.times(7)).orderRegion(orderId);
    }

    @Test
    void invoke_redactsToolResultBeforePersistenceAndReturningToConversation() {
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools,
                new ObjectMapper(), Clock.systemUTC());
        AgentPrincipal principal = new AgentPrincipal("viewer", Set.of("OPS_VIEWER"), Set.of("demo-region"));
        UUID toolCallId = UUID.randomUUID();
        when(repository.beginToolCall(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new com.peakdispatch.application.agent.AgentToolCallRecord(toolCallId,
                        "get_region_metrics", "args-hash", "RUNNING", null, null));
        when(businessTools.invoke(eq("get_region_metrics"), any(), eq("viewer"), eq("trace")))
                .thenReturn(Map.of("regionId", "demo-region", "email", "ops@example.com"));

        Map<String, Object> result = gateway.invoke(UUID.randomUUID(), AgentIntent.REGION_METRICS, "primary",
                "get_region_metrics", Map.of("regionId", "demo-region"), principal, "trace");

        assertThat(result).containsEntry("email", "[REDACTED]");
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(repository).completeToolCall(eq(toolCallId), persisted.capture(), anyLong(), any());
        assertThat(persisted.getValue()).contains("[REDACTED]").doesNotContain("ops@example.com");
    }

    @Test
    void invoke_withRegionOutsidePrincipalScope_auditsDenialAndDoesNotCallTool() {
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools,
                new ObjectMapper(), Clock.systemUTC());
        AgentPrincipal principal = new AgentPrincipal("viewer", Set.of("OPS_VIEWER"), Set.of("demo-region"));

        assertThatThrownBy(() -> gateway.invoke(UUID.randomUUID(), AgentIntent.REGION_METRICS, "primary",
                "get_region_metrics", Map.of("regionId", "blocked-region"), principal, "trace"))
                .isInstanceOf(AgentAccessDeniedException.class);

        verify(repository).audit(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
        verifyNoInteractions(businessTools);
        verify(repository, never()).beginToolCall(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void invoke_withInvalidWindowOrFractionalVersion_rejectsBeforeBusinessCall() {
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools,
                new ObjectMapper(), Clock.systemUTC());
        AgentPrincipal principal = new AgentPrincipal("approver", Set.of("OPS_APPROVER"), Set.of("demo-region"));

        assertThatThrownBy(() -> gateway.invoke(UUID.randomUUID(), AgentIntent.REGION_METRICS, "primary",
                "get_region_metrics", Map.of("regionId", "demo-region", "from", "2026-01-02T00:00:00Z",
                        "to", "2026-01-01T00:00:00Z"), principal, "trace"))
                .isInstanceOf(IllegalArgumentException.class);

        UUID orderId = UUID.randomUUID();
        when(businessTools.orderRegion(orderId)).thenReturn("demo-region");
        assertThatThrownBy(() -> gateway.invoke(UUID.randomUUID(), AgentIntent.CANCEL_ORDER, "primary",
                "request_cancel", Map.of("orderId", orderId.toString(), "expectedVersion", 1.5,
                        "reasonCode", "CUSTOMER_REQUEST"), principal, "trace"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, org.mockito.Mockito.times(2)).audit(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
        verify(repository, never()).beginToolCall(any(), anyString(), anyString(), anyString(), any());
        verify(businessTools, never()).invoke(anyString(), any(), anyString(), anyString());
    }

    @Test
    void validatePlan_acceptsMultiOrderRoutePreviewOnlyWithinOneAuthorizedRegion() {
        AgentToolGateway gateway = new AgentToolGateway(repository, businessTools,
                new ObjectMapper(), Clock.systemUTC());
        AgentPrincipal principal = new AgentPrincipal("viewer", Set.of("OPS_VIEWER"), Set.of("demo-region"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        when(businessTools.orderRegion(first)).thenReturn("demo-region");
        when(businessTools.orderRegion(second)).thenReturn("demo-region");
        when(businessTools.courierRegion(courier)).thenReturn("demo-region");

        Map<String, Object> validated = gateway.validatePlan(UUID.randomUUID(), AgentIntent.DISPATCH_SIMULATION,
                "simulate_dispatch", Map.of("orderIds", List.of(first.toString(), second.toString()),
                        "courierId", courier.toString()), principal, "trace");

        assertThat(validated.get("orderIds")).isEqualTo(List.of(first, second));
        assertThat(validated).containsEntry("courierId", courier).containsEntry("regionId", "demo-region");

        when(businessTools.orderRegion(second)).thenReturn("other-region");
        assertThatThrownBy(() -> gateway.validatePlan(UUID.randomUUID(), AgentIntent.DISPATCH_SIMULATION,
                "simulate_dispatch", Map.of("orderIds", List.of(first.toString(), second.toString()),
                        "courierId", courier.toString()), principal, "trace"))
                .isInstanceOf(AgentAccessDeniedException.class);
    }

    private record PlanCase(AgentIntent intent, String toolName, Map<String, Object> arguments) {
    }
}
