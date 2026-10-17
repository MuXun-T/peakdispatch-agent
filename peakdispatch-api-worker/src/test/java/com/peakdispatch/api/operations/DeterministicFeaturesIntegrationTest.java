package com.peakdispatch.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.agent.AgentConversationService;
import com.peakdispatch.application.agent.AgentPrincipal;
import com.peakdispatch.application.agent.AgentRepository;
import com.peakdispatch.application.courier.CourierPresenceService;
import com.peakdispatch.application.dispatch.BundleRoutePreviewService;
import com.peakdispatch.application.operations.PeakPlanAnalysisService;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.config.DemoDataInitializer;
import com.peakdispatch.domain.agent.AgentRunStatus;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeterministicFeaturesIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderApplicationService orders;
    @Autowired CourierPresenceService presence;
    @Autowired BundleRoutePreviewService previews;
    @Autowired PeakPlanAnalysisService peakPlans;
    @Autowired AgentConversationService agents;
    @Autowired AgentRepository agentRepository;
    @Autowired Clock clock;

    @Test
    void bundlePreview_obeysRouteCapacityAndDeadlineConstraintsWithoutChangingDispatchState() throws Exception {
        UUID courierId = createOnlineCourier(3);
        DeliveryOrder first = createWaitingOrder(Instant.now(clock).minusSeconds(300), Instant.now(clock).plusSeconds(7200));
        DeliveryOrder second = createWaitingOrder(Instant.now(clock).minusSeconds(300), Instant.now(clock).plusSeconds(7200));
        String body = mapper.writeValueAsString(Map.of("courierId", courierId,
                "orderIds", List.of(first.id(), second.id())));
        var auth = SecurityMockMvcRequestPostProcessors.httpBasic("ops-admin-test", "ops-admin-test-password");
        String key = "bundle-preview-" + UUID.randomUUID();

        MvcResult response = mockMvc.perform(post("/api/v1/ops/dispatch/bundle-previews").with(auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executable").value(true))
                .andExpect(jsonPath("$.data.mutatesDispatch").value(false))
                .andExpect(jsonPath("$.data.candidateOrders.length()").value(2))
                .andExpect(jsonPath("$.data.routeOrder.length()").value(4))
                .andReturn();
        String replay = mockMvc.perform(post("/api/v1/ops/dispatch/bundle-previews").with(auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(response.getResponse().getContentAsString());

        JsonNode preview = mapper.readTree(response.getResponse().getContentAsString()).path("data");
        Map<String, Integer> pickupIndexes = new java.util.HashMap<>();
        Map<String, Integer> dropoffIndexes = new java.util.HashMap<>();
        for (int i = 0; i < preview.path("routeOrder").size(); i++) {
            JsonNode stop = preview.path("routeOrder").get(i);
            ("PICKUP".equals(stop.path("action").asText()) ? pickupIndexes : dropoffIndexes)
                    .put(stop.path("orderId").asText(), i);
        }
        assertThat(pickupIndexes.keySet()).containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
        pickupIndexes.forEach((id, index) -> assertThat(index).isLessThan(dropoffIndexes.get(id)));
        assertThat(orders.get(first.id()).status()).isEqualTo(OrderStatus.WAITING_DISPATCH);
        assertThat(orders.get(second.id()).status()).isEqualTo(OrderStatus.WAITING_DISPATCH);
        assertThat(count("SELECT COUNT(*) FROM dispatch_offer WHERE order_id IN (?, ?)", first.id(), second.id()))
                .isZero();

        DeliveryOrder tightDeadline = createWaitingOrder(Instant.now(clock).minusSeconds(120),
                Instant.now(clock).plusSeconds(60));
        String deadlineBody = mapper.writeValueAsString(Map.of("courierId", courierId,
                "orderIds", List.of(tightDeadline.id())));
        JsonNode infeasible = mapper.readTree(mockMvc.perform(post("/api/v1/ops/dispatch/bundle-previews")
                        .with(auth).header("Idempotency-Key", "bundle-deadline-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(deadlineBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(infeasible.path("executable").asBoolean()).isFalse();
        assertThat(infeasible.path("failureReasons").toString()).contains("ORDER_DEADLINE_MISSED");
        assertThat(orders.get(tightDeadline.id()).status()).isEqualTo(OrderStatus.WAITING_DISPATCH);

        UUID limitedCourier = createOnlineCourier(2);
        List<UUID> threeOrders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            threeOrders.add(createWaitingOrder(Instant.now(clock).minusSeconds(120),
                    Instant.now(clock).plusSeconds(7200)).id());
        }
        String capacityBody = mapper.writeValueAsString(Map.of("courierId", limitedCourier, "orderIds", threeOrders));
        JsonNode capacityFailure = mapper.readTree(mockMvc.perform(post("/api/v1/ops/dispatch/bundle-previews")
                        .with(auth).header("Idempotency-Key", "bundle-capacity-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(capacityBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(capacityFailure.path("executable").asBoolean()).isFalse();
        assertThat(capacityFailure.path("failureReasons").toString()).contains("CAPACITY_EXCEEDED");
        assertThat(capacityFailure.path("capacity").path("projectedUsed").asInt()).isEqualTo(3);
        presence.setOffline(courierId);
        presence.setOffline(limitedCourier);
    }

    @Test
    void operationsSearch_returnsScopedPaginatedOrdersAndCourierCapacityAsOf() throws Exception {
        DeliveryOrder order = createWaitingOrder(Instant.now(clock).minusSeconds(120), Instant.now(clock).plusSeconds(7200));
        UUID courierId = createOnlineCourier(3);
        long generation = jdbc.queryForObject("SELECT presence_generation FROM courier WHERE id = ?", Long.class,
                bytes(courierId));
        var admin = SecurityMockMvcRequestPostProcessors.httpBasic("ops-admin-test", "ops-admin-test-password");

        mockMvc.perform(get("/api/v1/ops/orders").with(admin)
                        .queryParam("regionId", "demo-region").queryParam("status", "WAITING_DISPATCH")
                        .queryParam("orderId", order.id().toString()).queryParam("page", "0").queryParam("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.source").value("mysql_primary"))
                .andExpect(jsonPath("$.data.asOf").isNotEmpty());

        mockMvc.perform(get("/api/v1/ops/couriers").with(admin)
                        .queryParam("regionId", "demo-region").queryParam("onlineState", "ONLINE")
                        .queryParam("courierId", courierId.toString())
                        .queryParam("presenceGeneration", Long.toString(generation)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].presenceGeneration").value(generation))
                .andExpect(jsonPath("$.data.items[0].capacityLimit").value(3))
                .andExpect(jsonPath("$.data.items[0].availableCapacity").value(3))
                .andExpect(jsonPath("$.data.source").value("mysql_primary"))
                .andExpect(jsonPath("$.data.asOf").isNotEmpty());

        mockMvc.perform(get("/api/v1/ops/orders").with(
                        SecurityMockMvcRequestPostProcessors.httpBasic("ops-viewer-test", "ops-viewer-test-password"))
                        .queryParam("regionId", "demo-region"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id='ops-viewer-test'
                    AND action='OPS_ORDERS_READ' AND decision='DENIED' AND target_id='demo-region'
                """, Integer.class)).isGreaterThan(0);

        mockMvc.perform(get("/api/v1/ops/orders").with(admin)
                        .queryParam("regionId", "blocked-region"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ops/couriers").with(admin)
                        .queryParam("regionId", "blocked-region"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id='ops-admin-test'
                    AND action IN ('OPS_ORDERS_READ', 'OPS_COURIERS_READ')
                    AND decision='DENIED' AND target_id='blocked-region'
                """, Integer.class)).isEqualTo(2);
        presence.setOffline(courierId);
    }

    @Test
    void peakPlanComparison_isFixedSeedDeterministicAndAgentClarifiesWithoutCallingToolsWhenUncertain() throws Exception {
        Instant to = Instant.now(clock);
        Instant from = to.minusSeconds(4 * 3600);
        var first = peakPlans.compare("demo-region", from, to, 78321L, "approver-test", "peak-test");
        var repeat = peakPlans.compare("demo-region", from, to, 78321L, "approver-test", "peak-test");
        assertThat(first.rulesVersion()).isEqualTo("peak-capacity-scenarios-v1");
        assertThat(first.source()).isEqualTo("mysql_operations_dashboard");
        assertThat(first.plans()).isEqualTo(repeat.plans());
        assertThat(first.plans()).hasSize(3).allSatisfy(plan -> {
            assertThat(plan.rulesVersion()).isEqualTo(first.rulesVersion());
            assertThat(plan.inputMetrics()).containsKey("waitingDispatch");
            assertThat(plan.constraints()).containsEntry("mutatesDispatch", false);
            assertThat(plan.predictedOutcome().ordersPlannedThisCycle())
                    .isLessThanOrEqualTo(plan.predictedOutcome().capacityUnitsAvailable());
            assertThat(plan.limitations()).isNotEmpty();
        });

        AgentPrincipal requester = new AgentPrincipal("approver-test", Set.of("OPS_APPROVER"), Set.of("demo-region"));
        var peakRun = agents.chat(new AgentConversationService.ChatCommand("请比较区域高峰方案", null,
                "demo-region", null, null, null, null, null, from, to, null, 78321L),
                "peak-chat-" + UUID.randomUUID(), requester, "peak-test");
        assertThat(peakRun.status()).isEqualTo(AgentRunStatus.COMPLETED);
        var storedRun = agentRepository.findRun(peakRun.runId()).orElseThrow();
        JsonNode checkpoint = mapper.readTree(storedRun.checkpointJson());
        assertThat(checkpoint.path("result").path("rulesVersion").asText()).isEqualTo(first.rulesVersion());
        assertThat(checkpoint.path("result").path("plans").size()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT tool_name FROM agent_tool_call WHERE run_id = ?",
                String.class, bytes(peakRun.runId()))).isEqualTo("compare_peak_plans");

        var uncertain = agents.chat(new AgentConversationService.ChatCommand("请取消并改派订单", null,
                null, null, null, null, null, null, null, null), "uncertain-" + UUID.randomUUID(), requester,
                "peak-test");
        assertThat(uncertain.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(uncertain.intent().name()).isEqualTo("UNKNOWN");
        assertThat(agentRepository.eventsAfter(uncertain.runId(), 0, 20))
                .anySatisfy(event -> assertThat(event.eventType()).isEqualTo("clarification_required"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id = ?",
                Integer.class, bytes(uncertain.runId()))).isZero();
    }

    private UUID createOnlineCourier(int capacity) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO courier (id, region_id, online_state, presence_generation, capacity_limit, version)
                VALUES (?, 'demo-region', 'OFFLINE', 0, ?, 0)
                """, bytes(id), capacity);
        jdbc.update("""
                INSERT INTO courier_capacity (courier_id, capacity_limit, committed_count, reserved_count, version, updated_at)
                VALUES (?, ?, 0, 0, 0, ?)
                """, bytes(id), capacity, java.sql.Timestamp.from(now));
        var online = presence.setOnline(id, "demo-region");
        presence.heartbeat(id, "demo-region", online.presenceGeneration(), 1, 30.250, 120.160);
        return id;
    }

    private DeliveryOrder createWaitingOrder(Instant prepEta, Instant deadline) {
        DeliveryOrder created = orders.create(new OrderApplicationService.CreateOrderCommand(
                DemoDataInitializer.DEMO_MERCHANT_ID, DemoDataInitializer.DEMO_REGION_ID,
                30.250, 120.160, 30.251, 120.161, prepEta, deadline));
        return orders.transition(created.id(), created.version(), OrderStatus.CREATED, OrderStatus.WAITING_DISPATCH);
    }

    private int count(String sql, UUID... orderIds) {
        Object[] parameters = java.util.Arrays.stream(orderIds).map(DeterministicFeaturesIntegrationTest::bytes)
                .toArray();
        return jdbc.queryForObject(sql, Integer.class, parameters);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
