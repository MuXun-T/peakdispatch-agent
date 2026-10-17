package com.peakdispatch.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.agent.AgentAccessDeniedException;
import com.peakdispatch.application.agent.AgentActionRecord;
import com.peakdispatch.application.agent.AgentAnswerRenderer;
import com.peakdispatch.application.agent.AgentBusinessTools;
import com.peakdispatch.application.agent.AgentConversationService;
import com.peakdispatch.application.agent.AgentPrincipal;
import com.peakdispatch.application.agent.AgentRepository;
import com.peakdispatch.application.agent.AgentRunRecord;
import com.peakdispatch.application.agent.AgentTokenUsage;
import com.peakdispatch.application.courier.CourierPresenceService;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.application.agent.AgentIntent;
import com.peakdispatch.config.DemoDataInitializer;
import com.peakdispatch.domain.agent.AgentActionStatus;
import com.peakdispatch.domain.agent.AgentRunStatus;
import com.peakdispatch.domain.order.DeliveryOrder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentWorkflowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentConversationService agents;
    @Autowired AgentBusinessTools businessTools;
    @Autowired AgentRepository agentRepository;
    @Autowired OrderApplicationService orders;
    @Autowired CourierPresenceService presence;
    @Autowired DispatchService dispatch;
    @Autowired Clock clock;

    @Test
    void tokenUsage_isAggregatedPerRunWithoutPersistingModelContent() throws Exception {
        Instant now = Instant.now(clock);
        AgentRunRecord run = agentRepository.createRun(null, "approver-test", Set.of("OPS_APPROVER"),
                Set.of("demo-region"), "agent-token-" + UUID.randomUUID(), "区域指标",
                mapper.writeValueAsString(Map.of("message", "区域指标", "regionId", "demo-region")),
                "token-input-hash", "agent-token-test", "deterministic-mock-v1", "mock-v1", now);

        agentRepository.recordTokenUsage(run.runId(), new AgentTokenUsage(19, 7));
        agentRepository.recordTokenUsage(run.runId(), new AgentTokenUsage(1, 2));

        JsonNode usage = mapper.readTree(jdbc.queryForObject("SELECT token_usage_json FROM agent_run WHERE id = ?",
                String.class, bytes(run.runId())));
        assertThat(usage.path("input").asLong()).isEqualTo(20);
        assertThat(usage.path("output").asLong()).isEqualTo(9);
        assertThat(usage.path("total").asLong()).isEqualTo(29);
        assertThat(usage.toString()).doesNotContain("区域指标", "model", "choices");

        mockMvc.perform(get("/api/v1/ops/runs/" + run.runId()).with(
                        SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenUsage.inputTokens").value(20))
                .andExpect(jsonPath("$.data.tokenUsage.outputTokens").value(9))
                .andExpect(jsonPath("$.data.tokenUsage.totalTokens").value(29));
    }

    @Test
    void clarificationCanContinueInTheSameSessionWhenUserSuppliesTheMissingOrderId() throws Exception {
        AgentPrincipal requester = principal("approver-test", "OPS_APPROVER");
        AgentConversationService.RunSummary question = agents.chat(new AgentConversationService.ChatCommand(
                "请诊断订单", null, "demo-region", null, null, null, null, null, null, null),
                "agent-clarify-first-" + UUID.randomUUID(), requester, "agent-clarify-test");
        List<com.peakdispatch.application.agent.AgentConversationTurn> firstTurn = agents.conversation(
                question.sessionId(), requester);
        assertThat(firstTurn).hasSize(1);
        assertThat(firstTurn.getFirst().pendingClarification()).isTrue();
        assertThat(firstTurn.getFirst().missingFields()).contains("orderId");

        DeliveryOrder order = createOrder();
        AgentConversationService.RunSummary answer = agents.chat(new AgentConversationService.ChatCommand(
                order.id().toString(), question.sessionId(), "demo-region", null, null, null,
                null, null, null, null), "agent-clarify-second-" + UUID.randomUUID(), requester,
                "agent-clarify-test");

        assertThat(answer.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(answer.intent()).isEqualTo(AgentIntent.ORDER_DIAGNOSIS);
        JsonNode toolResult = mapper.readTree(jdbc.queryForObject(
                "SELECT result_json FROM agent_tool_call WHERE run_id = ?", String.class, bytes(answer.runId())));
        assertThat(toolResult.path("orderId").asText()).isEqualTo(order.id().toString());
        assertThat(agents.conversation(question.sessionId(), requester)).hasSize(2);

        mockMvc.perform(get("/api/v1/ops/sessions/" + question.sessionId() + "/turns").with(
                        SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void clarificationCanContinueInTheSameSessionWhenUserSuppliesTheMissingRegion() throws Exception {
        AgentPrincipal requester = principal("approver-test", "OPS_APPROVER");
        AgentConversationService.RunSummary question = agents.chat(new AgentConversationService.ChatCommand(
                "查看区域指标", null, null, null, null, null, null, null, null, null),
                "agent-region-first-" + UUID.randomUUID(), requester, "agent-clarify-test");
        List<com.peakdispatch.application.agent.AgentConversationTurn> firstTurn = agents.conversation(
                question.sessionId(), requester);
        assertThat(firstTurn.getFirst().pendingClarification()).isTrue();
        assertThat(firstTurn.getFirst().missingFields()).contains("regionId");

        AgentConversationService.RunSummary answer = agents.chat(new AgentConversationService.ChatCommand(
                " demo-region ", question.sessionId(), null, null, null, null, null, null, null, null),
                "agent-region-second-" + UUID.randomUUID(), requester, "agent-clarify-test");

        assertThat(answer.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(answer.intent()).isEqualTo(AgentIntent.REGION_METRICS);
        JsonNode toolResult = mapper.readTree(jdbc.queryForObject(
                "SELECT result_json FROM agent_tool_call WHERE run_id = ?", String.class, bytes(answer.runId())));
        assertThat(toolResult.path("regionId").asText()).isEqualTo("demo-region");
        assertThat(agents.conversation(question.sessionId(), requester)).hasSize(2);
    }

    @Test
    void clarificationRetainsOrderAndVersionAcrossMultipleFollowUpTurns() throws Exception {
        DeliveryOrder order = createOrder();
        AgentPrincipal requester = principal("ops-admin-test", "OPS_ADMIN");
        AgentConversationService.RunSummary question = agents.chat(new AgentConversationService.ChatCommand(
                "取消订单 " + order.id(), null, "demo-region", null, null, null, null, null, null, null),
                "agent-cancel-first-" + UUID.randomUUID(), requester, "agent-clarify-test");
        var firstTurn = agents.conversation(question.sessionId(), requester).getFirst();
        assertThat(firstTurn.pendingClarification()).isTrue();
        assertThat(firstTurn.missingFields()).contains("expectedVersion", "reasonCode");
        assertThat(firstTurn.suppliedSlots()).containsEntry("orderId", order.id().toString());

        AgentConversationService.RunSummary versionPrompt = agents.chat(new AgentConversationService.ChatCommand(
                " " + order.version() + " ", question.sessionId(), "demo-region", null, null, null,
                null, null, null, null), "agent-cancel-version-" + UUID.randomUUID(), requester,
                "agent-clarify-test");
        assertThat(versionPrompt.status()).isEqualTo(AgentRunStatus.COMPLETED);
        var secondTurn = agents.conversation(question.sessionId(), requester).getLast();
        assertThat(secondTurn.pendingClarification()).isTrue();
        assertThat(secondTurn.missingFields()).containsExactly("reasonCode");
        assertThat(secondTurn.suppliedSlots()).containsEntry("orderId", order.id().toString());
        assertThat(((Number) secondTurn.suppliedSlots().get("expectedVersion")).longValue())
                .isEqualTo(order.version());

        AgentConversationService.RunSummary reason = agents.chat(new AgentConversationService.ChatCommand(
                " CUSTOMER_REQUEST ", question.sessionId(), "demo-region", null, null, null,
                null, null, null, null), "agent-cancel-reason-" + UUID.randomUUID(), requester,
                "agent-clarify-test");
        assertThat(reason.status()).isEqualTo(AgentRunStatus.WAITING_CONFIRMATION);
        AgentActionRecord action = agentRepository.findAction(UUID.fromString(reason.actionId())).orElseThrow();
        JsonNode parameters = mapper.readTree(action.paramsJson());
        assertThat(action.targetId()).isEqualTo(order.id());
        assertThat(action.expectedVersion()).isEqualTo(order.version());
        assertThat(parameters.path("reasonCode").asText()).isEqualTo("CUSTOMER_REQUEST");
    }

    @Test
    void approvalRequiredEventContainsTheCompleteRescheduleParameters() throws Exception {
        DeliveryOrder order = createOrder();
        Instant newPrepEta = Instant.parse("2035-01-01T10:20:00Z");
        Instant newDeadline = Instant.parse("2035-01-01T11:10:00Z");
        AgentConversationService.RunSummary run = agents.chat(new AgentConversationService.ChatCommand(
                "reschedule", null, "demo-region", order.id(), order.version(), "MERCHANT_DELAY",
                newPrepEta, newDeadline, null, null), "agent-reschedule-" + UUID.randomUUID(),
                principal("ops-admin-test", "OPS_ADMIN"), "agent-reschedule-test");

        JsonNode approval = mapper.valueToTree(agentRepository.eventsAfter(run.runId(), 0, 100).stream()
                .filter(event -> "approval_required".equals(event.eventType())).findFirst().orElseThrow().payload());

        assertThat(approval.path("targetId").asText()).isEqualTo(order.id().toString());
        assertThat(approval.path("targetVersion").asLong()).isEqualTo(order.version());
        assertThat(approval.path("parameters").path("reasonCode").asText()).isEqualTo("MERCHANT_DELAY");
        assertThat(approval.path("parameters").path("prepEta").asText()).isEqualTo(newPrepEta.toString());
        assertThat(approval.path("parameters").path("deadline").asText()).isEqualTo(newDeadline.toString());
        assertThat(approval.path("summary").asText()).contains("MERCHANT_DELAY", newPrepEta.toString(),
                newDeadline.toString());
        assertThat(approval.path("summaryHash").asText()).hasSize(64);
    }

    @Test
    void dispatchSimulationReturnsDeterministicReadOnlyRouteEvidence() {
        DeliveryOrder order = createOrder();
        UUID courierId = UUID.randomUUID();
        Instant heartbeat = Instant.now(clock);
        jdbc.update("""
                INSERT INTO courier (id, username, region_id, online_state, presence_generation,
                    capacity_limit, version, heartbeat_sequence, latitude, longitude, last_position_at)
                VALUES (?, NULL, 'demo-region', 'ONLINE', 1, 3, 0, 1, 30.250, 120.160, ?)
                """, bytes(courierId), Timestamp.from(heartbeat));
        jdbc.update("""
                INSERT INTO courier_capacity (courier_id, capacity_limit, committed_count,
                    reserved_count, version, updated_at) VALUES (?, 3, 0, 0, 0, ?)
                """, bytes(courierId), Timestamp.from(heartbeat));
        Map<String, Object> result = businessTools.invoke("simulate_dispatch",
                Map.of("regionId", "demo-region", "orderIds", List.of(order.id())),
                "approver-test", "agent-simulation-test");

        assertThat(result).containsKeys("simulationId", "ruleVersion", "inputVersions", "candidateCount",
                "candidates", "constraints", "mutatesDispatch");
        assertThat(result).containsEntry("regionId", "demo-region").containsEntry("mutatesDispatch", false);
        assertThat(((Map<?, ?>) result.get("inputVersions")).get(order.id().toString()))
                .isEqualTo(order.version());
        assertThat((List<?>) result.get("candidates")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_offer WHERE order_id = ?",
                Integer.class, bytes(order.id()))).isZero();
    }

    @Test
    void diagnosis_bindsPersistedDispatchEvidenceAndRendererUsesOnlyBoundFacts() {
        DeliveryOrder order = createOrder();
        UUID courierId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant startedAt = Instant.now(clock).minusSeconds(5);
        jdbc.update("""
                INSERT INTO courier (id, region_id, online_state, presence_generation, capacity_limit, version)
                VALUES (?, 'demo-region', 'OFFLINE', 0, 3, 0)
                """, bytes(courierId));
        jdbc.update("""
                INSERT INTO dispatch_attempt (id, order_id, round_no, order_version, estimator_version, outcome,
                    candidate_count, exclusion_summary, started_at, finished_at, trace_id)
                VALUES (?, ?, 1, ?, 'diagnosis-test-v1', 'NO_CANDIDATE', 1,
                    'candidate excluded by capacity', ?, ?, 'diagnosis-trace')
                """, bytes(attemptId), bytes(order.id()), order.version(), Timestamp.from(startedAt),
                Timestamp.from(startedAt.plusMillis(25)));
        jdbc.update("""
                INSERT INTO dispatch_candidate_decision (attempt_id, courier_id, eligible, reason_code,
                    rank_no, score, feature_snapshot)
                VALUES (?, ?, FALSE, 'CAPACITY_EXCEEDED', NULL, 0.875000,
                    '{"capacityLimit":3,"reserved":3,"distanceKm":1.2}')
                """, bytes(attemptId), bytes(courierId));

        Map<String, Object> result = businessTools.invoke("diagnose_order",
                Map.of("orderId", order.id().toString()), "approver-test", "diagnosis-trace");
        JsonNode resultJson = mapper.valueToTree(result);
        JsonNode round = resultJson.path("dispatchRounds").get(0);
        String attemptEvidenceId = "attempt:" + attemptId;
        String candidateEvidenceId = "candidate:" + attemptId + ":" + courierId;
        assertThat(resultJson.path("dispatchRounds")).hasSize(1);
        assertThat(round.path("evidenceId").asText()).isEqualTo(attemptEvidenceId);
        assertThat(round.path("outcome").asText()).isEqualTo("NO_CANDIDATE");
        assertThat(round.path("candidates")).hasSize(1);
        assertThat(round.path("candidates").get(0).path("evidenceId").asText())
                .isEqualTo(candidateEvidenceId);
        assertThat(round.path("candidates").get(0).path("reasonCode").asText())
                .isEqualTo("CAPACITY_EXCEEDED");
        assertThat(round.path("candidates").get(0).path("score").asDouble()).isEqualTo(0.875D);
        assertThat(round.path("candidates").get(0).path("featureSnapshot").asText())
                .contains("capacityLimit", "reserved");
        assertThat(resultJson.path("reasonCodes").toString()).contains("NO_ELIGIBLE_COURIER_RECORDED");
        assertThat(resultJson.path("reasonEvidence").path("NO_ELIGIBLE_COURIER_RECORDED").toString())
                .contains(attemptEvidenceId);
        assertThat(resultJson.path("evidence").toString()).contains(attemptEvidenceId, candidateEvidenceId);

        String answer = AgentAnswerRenderer.render(com.peakdispatch.application.agent.AgentIntent.ORDER_DIAGNOSIS,
                result, mapper);
        assertThat(answer).contains("NO_ELIGIBLE_COURIER_RECORDED", attemptEvidenceId)
                .doesNotContain("CAPACITY_EXCEEDED");
    }

    @Test
    void chat_persistsMockRunAndSseEvents_resumeFromLastEventIdAndDeduplicateRequest() throws Exception {
        String key = "agent-chat-" + UUID.randomUUID();
        String body = "{\"message\":\"区域指标\",\"regionId\":\"demo-region\"}";
        var auth = SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password");

        MvcResult initial = mockMvc.perform(post("/api/v1/ops/chat").with(auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        MvcResult streamed = mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        String runId = streamed.getResponse().getHeader("X-Agent-Run-Id");
        String frames = streamed.getResponse().getContentAsString();

        assertThat(runId).isNotBlank();
        assertThat(frames).contains("event: intent", "event: tool_start", "event: message", "event: done");
        assertThat(jdbc.queryForObject("SELECT status FROM agent_run WHERE id = ?", String.class,
                bytes(UUID.fromString(runId)))).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT model_id FROM agent_run WHERE id = ?", String.class,
                bytes(UUID.fromString(runId)))).isEqualTo("deterministic-mock-v1");
        assertThat(jdbc.queryForObject("SELECT prompt_version FROM agent_run WHERE id = ?", String.class,
                bytes(UUID.fromString(runId)))).isEqualTo("mock-v1");

        MvcResult replayStart = mockMvc.perform(get("/api/v1/ops/runs/" + runId + "/events").with(auth)
                        .header("Last-Event-ID", runId + ":1"))
                .andExpect(request().asyncStarted()).andReturn();
        String resumed = mockMvc.perform(asyncDispatch(replayStart)).andReturn().getResponse().getContentAsString();
        assertThat(resumed).doesNotContain("id: " + runId + ":1\n")
                .contains("id: " + runId + ":2\n", "event: message", "event: done");

        MvcResult duplicateStart = mockMvc.perform(post("/api/v1/ops/chat").with(auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        String duplicate = mockMvc.perform(asyncDispatch(duplicateStart)).andReturn().getResponse().getContentAsString();
        assertThat(duplicate).isEqualTo(frames);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE owner_id = ? AND client_request_key = ?",
                Integer.class, "approver-test", key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id = ?", Integer.class,
                bytes(UUID.fromString(runId)))).isEqualTo(1);

        JsonNode recordedToolResult = mapper.readTree(jdbc.queryForObject(
                "SELECT result_json FROM agent_tool_call WHERE run_id = ?", String.class,
                bytes(UUID.fromString(runId))));
        JsonNode finalMessage = mapper.readTree(jdbc.queryForObject("""
                SELECT payload_redacted FROM agent_run_event
                WHERE run_id = ? AND event_type = 'message' AND dedupe_key = 'final_message'
                """, String.class, bytes(UUID.fromString(runId))));
        String groundedAnswer = finalMessage.path("content").asText();
        assertThat(groundedAnswer).contains("区域 " + recordedToolResult.path("regionId").asText())
                .contains("待派 " + recordedToolResult.path("metrics").path("waitingDispatch").asText())
                .contains("在线骑手 " + recordedToolResult.path("metrics").path("onlineCouriers").asText())
                .contains("派单失败 " + recordedToolResult.path("metrics").path("dispatchFailures").asText());
    }

    @Test
    void recovery_completedToolCheckpointFinishesFromSavedResultWithoutRepeatingToolCall() throws Exception {
        AgentConversationService.RunSummary run = agents.chat(new AgentConversationService.ChatCommand(
                "区域指标", null, "demo-region", null, null, null, null, null, null, null),
                "agent-recovery-checkpoint-" + UUID.randomUUID(),
                principal("approver-test", "OPS_APPROVER"), "agent-recovery-checkpoint");
        UUID runId = run.runId();
        String resultJson = jdbc.queryForObject("SELECT result_json FROM agent_tool_call WHERE run_id = ?",
                String.class, bytes(runId));
        Map<String, Object> checkpoint = new java.util.LinkedHashMap<>();
        checkpoint.put("phase", "TOOL_COMPLETED");
        checkpoint.put("toolName", "get_region_metrics");
        checkpoint.put("toolArguments", Map.of("regionId", "demo-region", "granularity", "hour"));
        checkpoint.put("toolCallKey", "primary");
        checkpoint.put("intentConfidence", 0.96);
        checkpoint.put("toolResult", mapper.readValue(resultJson, Map.class));
        jdbc.update("UPDATE agent_run SET status = 'RUNNING', checkpoint_json = ?, ended_at = NULL, updated_at = ? WHERE id = ?",
                mapper.writeValueAsString(checkpoint), Timestamp.from(Instant.now(clock).minusSeconds(30)), bytes(runId));

        assertThat(agents.recoverStaleRuns()).isGreaterThanOrEqualTo(1);

        assertThat(agents.summary(runId, principal("approver-test", "OPS_APPROVER")).status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id = ?", Integer.class,
                bytes(runId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id = ? AND event_type = 'tool_start'",
                Integer.class, bytes(runId))).isEqualTo(1);
    }

    @Test
    void persistedAgentEvents_redactCredentialsFromStructuredPayloads() {
        AgentConversationService.RunSummary run = agents.chat(new AgentConversationService.ChatCommand(
                "区域指标", null, null, null, null, null, null, null, null, null),
                "agent-redaction-" + UUID.randomUUID(), principal("approver-test", "OPS_APPROVER"), "redaction-test");
        UUID runId = run.runId();

        var stored = agentRepository.storeEvent(runId, AgentRunStatus.COMPLETED, AgentIntent.REGION_METRICS,
                Map.of(), "message", "redaction-contract", Map.of(
                        "content", "email=ops@example.com phone=13800138000",
                        "Authorization", "Bearer raw-auth-secret",
                        "address", "杭州市西湖区文三路88号"), Instant.now(clock));

        assertThat(stored.payload().toString()).contains("[EMAIL]", "[PHONE]", "[REDACTED]")
                .doesNotContain("ops@example.com", "13800138000", "raw-auth-secret", "文三路88号");
        String persisted = jdbc.queryForObject("""
                SELECT payload_redacted FROM agent_run_event WHERE run_id = ? AND dedupe_key = 'redaction-contract'
                """, String.class, bytes(runId));
        assertThat(persisted).doesNotContain("ops@example.com", "13800138000", "raw-auth-secret", "文三路88号");
    }

    @Test
    void chat_withUnauthorizedRegion_failsClosedAndAuditsToolDenial() throws Exception {
        String key = "agent-scope-" + UUID.randomUUID();
        MvcResult initial = mockMvc.perform(post("/api/v1/ops/chat")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("ops-viewer-test", "ops-viewer-test-password"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"区域指标\",\"regionId\":\"demo-region\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult response = mockMvc.perform(asyncDispatch(initial)).andReturn();
        String runId = response.getResponse().getHeader("X-Agent-Run-Id");

        assertThat(response.getResponse().getContentAsString()).contains("event: error");
        assertThat(jdbc.queryForObject("SELECT status FROM agent_run WHERE id = ?", String.class,
                bytes(UUID.fromString(runId)))).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id = 'ops-viewer-test'
                    AND action = 'AGENT_TOOL_CALL' AND decision = 'DENIED'
                """, Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id = ?", Integer.class,
                bytes(UUID.fromString(runId)))).isZero();
    }

    @Test
    void approval_requiresAnotherApproverAndConcurrentDuplicateApprovalsExecuteOnce() throws Exception {
        DeliveryOrder order = createOrder();
        AgentPrincipal requester = principal("ops-admin-test", "OPS_ADMIN");
        AgentConversationService.RunSummary run = agents.chat(new AgentConversationService.ChatCommand(
                "请取消订单", null, null, order.id(), order.version(), "CUSTOMER_REQUEST",
                null, null, null, null), "agent-action-" + UUID.randomUUID(), requester, "agent-test");
        assertThat(run.status()).isEqualTo(AgentRunStatus.WAITING_CONFIRMATION);
        UUID actionId = UUID.fromString(run.actionId());
        AgentPrincipal firstApprover = principal("approver-test", "OPS_APPROVER");
        AgentPrincipal secondApprover = principal("approver-backup-test", "OPS_APPROVER");

        assertThatThrownBy(() -> agents.approve(actionId, requester, "agent-test"))
                .isInstanceOf(AgentAccessDeniedException.class);
        assertThat(agentRepository.findAction(actionId).orElseThrow().status())
                .isEqualTo(AgentActionStatus.PENDING_CONFIRMATION);

        CompletableFuture<AgentActionRecord> first = CompletableFuture.supplyAsync(
                () -> agents.approve(actionId, firstApprover, "agent-approval-race"));
        CompletableFuture<AgentActionRecord> second = CompletableFuture.supplyAsync(
                () -> agents.approve(actionId, secondApprover, "agent-approval-race"));
        AgentActionRecord a = first.join();
        AgentActionRecord b = second.join();

        assertThat(a.status()).isEqualTo(AgentActionStatus.COMPLETED);
        assertThat(b.status()).isEqualTo(AgentActionStatus.COMPLETED);
        assertThat(orders.get(order.id()).status().name()).isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.cancelled'",
                order.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic = 'agent.action.resolved' AND aggregate_id = ?",
                Integer.class, bytes(actionId))).isEqualTo(1);

        AgentActionRecord replay = agents.approve(actionId, firstApprover, "agent-test");
        assertThat(replay.status()).isEqualTo(AgentActionStatus.COMPLETED);
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.cancelled'",
                order.id())).isEqualTo(1);
    }

    @Test
    void rejectAndExpire_resolveWaitingRunWithoutExecutingOrderCommand() throws Exception {
        DeliveryOrder rejectedOrder = createOrder();
        AgentPrincipal requester = principal("ops-admin-test", "OPS_ADMIN");
        AgentConversationService.RunSummary rejectedRun = createCancelRun(rejectedOrder, requester);
        UUID rejectedAction = UUID.fromString(rejectedRun.actionId());
        assertThat(agents.reject(rejectedAction, "NOT_NEEDED", requester, "agent-test").status())
                .isEqualTo(AgentActionStatus.REJECTED);
        assertThat(orders.get(rejectedOrder.id()).status().name()).isEqualTo("CREATED");
        assertThat(agents.summary(rejectedRun.runId(), requester).status()).isEqualTo(AgentRunStatus.COMPLETED);

        DeliveryOrder expiredOrder = createOrder();
        AgentConversationService.RunSummary expiredRun = createCancelRun(expiredOrder, requester);
        UUID expiredAction = UUID.fromString(expiredRun.actionId());
        jdbc.update("UPDATE agent_action SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now(clock).minusSeconds(1)), bytes(expiredAction));
        assertThat(agents.expireDueActions()).isGreaterThanOrEqualTo(1);
        assertThat(agentRepository.findAction(expiredAction).orElseThrow().status())
                .isEqualTo(AgentActionStatus.EXPIRED);
        assertThat(orders.get(expiredOrder.id()).status().name()).isEqualTo("CREATED");
    }

    @Test
    void reassignmentApproval_isIdempotentReleasesOldOfferAndOnlyAcceptBindsTheNextCourier() throws Exception {
        DeliveryOrder order = createOfferedOrder();
        long offeredVersion = order.version();
        DispatchService.CourierOffer oldOffer = dispatch.activeOffers(DemoDataInitializer.DEMO_COURIER_ID)
                .stream().filter(offer -> offer.orderId().equals(order.id())).findFirst().orElseThrow();
        int reservedBefore = capacityCount("reserved_count", DemoDataInitializer.DEMO_COURIER_ID);
        int committedBefore = capacityCount("committed_count", DemoDataInitializer.DEMO_COURIER_ID);
        assertThat(reservedBefore).isGreaterThanOrEqualTo(1);

        String key = "reassign-request-" + UUID.randomUUID();
        String body = "{\"expectedVersion\":" + offeredVersion + ",\"reasonCode\":\"COURIER_REQUEST\"}";
        var requesterAuth = SecurityMockMvcRequestPostProcessors.httpBasic("ops-admin-test", "ops-admin-test-password");
        MvcResult firstRequest = mockMvc.perform(post("/api/v1/ops/orders/" + order.id() + "/reassignment-requests")
                        .with(requesterAuth).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String replayBody = mockMvc.perform(post("/api/v1/ops/orders/" + order.id() + "/reassignment-requests")
                        .with(requesterAuth).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replayBody).isEqualTo(firstRequest.getResponse().getContentAsString());
        JsonNode requestResult = mapper.readTree(replayBody).path("data");
        assertThat(requestResult.path("status").asText()).isEqualTo("WAITING_CONFIRMATION");
        UUID actionId = UUID.fromString(requestResult.path("actionId").asText());

        AgentPrincipal requester = principal("ops-admin-test", "OPS_ADMIN");
        assertThatThrownBy(() -> agents.approve(actionId, requester, "reassign-self-approval"))
                .isInstanceOf(AgentAccessDeniedException.class);
        assertThat(agentRepository.findAction(actionId).orElseThrow().status())
                .isEqualTo(AgentActionStatus.PENDING_CONFIRMATION);

        AgentPrincipal firstApprover = principal("approver-test", "OPS_APPROVER");
        AgentPrincipal secondApprover = principal("approver-backup-test", "OPS_APPROVER");
        CompletableFuture<AgentActionRecord> first = CompletableFuture.supplyAsync(
                () -> agents.approve(actionId, firstApprover, "reassign-approval-race"));
        CompletableFuture<AgentActionRecord> second = CompletableFuture.supplyAsync(
                () -> agents.approve(actionId, secondApprover, "reassign-approval-race"));
        assertThat(first.join().status()).isEqualTo(AgentActionStatus.COMPLETED);
        assertThat(second.join().status()).isEqualTo(AgentActionStatus.COMPLETED);

        DeliveryOrder requeued = orders.get(order.id());
        assertThat(requeued.status().name()).isEqualTo("WAITING_DISPATCH");
        assertThat(requeued.version()).isEqualTo(offeredVersion + 1);
        assertThat(agentRepository.findAction(actionId).orElseThrow().resultRef())
                .contains("WAITING_DISPATCH").contains("newCourierConfirmed=false");
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?", String.class,
                bytes(oldOffer.offerId()))).isEqualTo("SUPERSEDED");
        assertThat(capacityCount("reserved_count", DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(reservedBefore - 1);
        assertThat(capacityCount("committed_count", DemoDataInitializer.DEMO_COURIER_ID)).isEqualTo(committedBefore);
        assertThat(jdbc.queryForObject("SELECT phase FROM order_dispatch_slot WHERE order_id = ?", String.class,
                bytes(order.id()))).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE order_id = ? AND released_at IS NULL",
                Integer.class, bytes(order.id()))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic='order.dispatch_requested' AND aggregate_id=?",
                Integer.class, bytes(order.id()))).isGreaterThan(0);

        DispatchRepository.OfferCommandResult staleOffer = dispatch.accept(DemoDataInitializer.DEMO_COURIER_ID,
                oldOffer.offerId(), oldOffer.offerToken(), oldOffer.orderVersion());
        assertThat(staleOffer.changed()).isFalse();
        assertThat(staleOffer.order().status().name()).isEqualTo("WAITING_DISPATCH");

        DispatchService.DispatchDecision nextOffer = dispatch.tryDispatch(order.id());
        assertThat(nextOffer.outcome()).isEqualTo("OFFER_CREATED");
        assertThat(orders.get(order.id()).status().name()).isEqualTo("OFFERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE order_id = ? AND released_at IS NULL",
                Integer.class, bytes(order.id()))).isZero();
        DispatchRepository.OfferCommandResult accepted = dispatch.accept(nextOffer.offer().courierId(),
                nextOffer.offer().offerId(), nextOffer.offer().offerToken(), nextOffer.offer().orderVersion());
        assertThat(accepted.order().status().name()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE order_id = ? AND released_at IS NULL",
                Integer.class, bytes(order.id()))).isEqualTo(1);
        orders.reassign(order.id(), accepted.order().version(), "TEST_CLEANUP", "agent-test");
    }

    @Test
    void reassignmentApproval_invalidatesOnVersionOrRegionChangeAndDoesNothingAfterExpiry() {
        DeliveryOrder changedOrder = createOfferedOrder();
        AgentConversationService.RunSummary changedRun = createReassignmentRun(changedOrder);
        UUID changedAction = UUID.fromString(changedRun.actionId());
        AgentPrincipal outsideRegion = new AgentPrincipal("outside-approver", Set.of("OPS_APPROVER"), Set.of("blocked-region"));
        assertThatThrownBy(() -> agents.approve(changedAction, outsideRegion, "reassign-scope"))
                .isInstanceOf(AgentAccessDeniedException.class);
        assertThat(agentRepository.findAction(changedAction).orElseThrow().status())
                .isEqualTo(AgentActionStatus.PENDING_CONFIRMATION);
        DispatchService.CourierOffer issued = dispatch.activeOffers(DemoDataInitializer.DEMO_COURIER_ID)
                .stream().filter(offer -> offer.orderId().equals(changedOrder.id())).findFirst().orElseThrow();
        dispatch.accept(DemoDataInitializer.DEMO_COURIER_ID, issued.offerId(), issued.offerToken(), issued.orderVersion());
        AgentActionRecord invalidated = agents.approve(changedAction,
                principal("approver-test", "OPS_APPROVER"), "reassign-version-conflict");
        assertThat(invalidated.status()).isEqualTo(AgentActionStatus.INVALIDATED);
        assertThat(orders.get(changedOrder.id()).status().name()).isEqualTo("ACCEPTED");
        orders.reassign(changedOrder.id(), orders.get(changedOrder.id()).version(), "TEST_CLEANUP", "agent-test");

        DeliveryOrder expiringOrder = createOfferedOrder();
        AgentConversationService.RunSummary expiringRun = createReassignmentRun(expiringOrder);
        UUID expiringAction = UUID.fromString(expiringRun.actionId());
        jdbc.update("UPDATE agent_action SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock).minusSeconds(1)), bytes(expiringAction));
        AgentActionRecord expired = agents.approve(expiringAction,
                principal("approver-test", "OPS_APPROVER"), "reassign-expiry");
        assertThat(expired.status()).isEqualTo(AgentActionStatus.EXPIRED);
        assertThat(orders.get(expiringOrder.id()).status().name()).isEqualTo("OFFERED");
        orders.reassign(expiringOrder.id(), orders.get(expiringOrder.id()).version(), "TEST_CLEANUP", "agent-test");
    }

    @Test
    void reassignmentApproval_releasesCommittedCapacityAndCourierBindingForAcceptedOrder() {
        DeliveryOrder offered = createOfferedOrder();
        DispatchService.CourierOffer oldOffer = dispatch.activeOffers(DemoDataInitializer.DEMO_COURIER_ID)
                .stream().filter(offer -> offer.orderId().equals(offered.id())).findFirst().orElseThrow();
        DispatchRepository.OfferCommandResult accepted = dispatch.accept(DemoDataInitializer.DEMO_COURIER_ID,
                oldOffer.offerId(), oldOffer.offerToken(), oldOffer.orderVersion());
        assertThat(accepted.order().status().name()).isEqualTo("ACCEPTED");
        int committedBefore = capacityCount("committed_count", DemoDataInitializer.DEMO_COURIER_ID);
        int reservedBefore = capacityCount("reserved_count", DemoDataInitializer.DEMO_COURIER_ID);
        assertThat(committedBefore).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE order_id = ? "
                        + "AND status = 'ACCEPTED' AND released_at IS NULL", Integer.class, bytes(offered.id())))
                .isEqualTo(1);

        AgentConversationService.RunSummary run = createReassignmentRun(orders.get(offered.id()));
        UUID actionId = UUID.fromString(run.actionId());
        AgentActionRecord result = agents.approve(actionId, principal("approver-test", "OPS_APPROVER"),
                "reassign-accepted-order");

        assertThat(result.status()).isEqualTo(AgentActionStatus.COMPLETED);
        DeliveryOrder requeued = orders.get(offered.id());
        assertThat(requeued.status().name()).isEqualTo("WAITING_DISPATCH");
        assertThat(requeued.version()).isEqualTo(accepted.order().version() + 1);
        assertThat(capacityCount("committed_count", DemoDataInitializer.DEMO_COURIER_ID))
                .isEqualTo(committedBefore - 1);
        assertThat(capacityCount("reserved_count", DemoDataInitializer.DEMO_COURIER_ID))
                .isEqualTo(reservedBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_assignment WHERE order_id = ? "
                        + "AND status = 'RELEASED' AND released_at IS NOT NULL AND release_reason = 'REASSIGNED'",
                Integer.class, bytes(offered.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phase FROM order_dispatch_slot WHERE order_id = ?",
                String.class, bytes(offered.id()))).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT courier_id FROM order_dispatch_slot WHERE order_id = ?",
                byte[].class, bytes(offered.id()))).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_offer WHERE id = ?",
                String.class, bytes(oldOffer.offerId()))).isEqualTo("ACCEPTED");
    }

    @Test
    void recovery_resumesStaleRunFromPersistedCheckpointWithoutDuplicatingToolStart() throws Exception {
        Instant old = Instant.now(clock).minusSeconds(30);
        String input = mapper.writeValueAsString(Map.of("message", "区域指标", "regionId", "demo-region"));
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        AgentRunRecord run = agentRepository.createRun(null, "approver-test", Set.of("OPS_APPROVER"),
                Set.of("demo-region"), "agent-recovery-" + UUID.randomUUID(), "区域指标", input,
                hash, "agent-test", "deterministic-mock-v1", "mock-v1", old);
        agentRepository.storeEvent(run.runId(), AgentRunStatus.RUNNING,
                com.peakdispatch.application.agent.AgentIntent.REGION_METRICS,
                Map.of("phase", "TOOL_PENDING", "toolName", "get_region_metrics"), "tool_start",
                "tool_start:primary", Map.of("toolCallId", "primary", "toolName", "get_region_metrics"), old);

        assertThat(agents.recoverStaleRuns()).isGreaterThanOrEqualTo(1);

        assertThat(agentRepository.findRun(run.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id = ? AND dedupe_key = 'tool_start:primary'",
                Integer.class, bytes(run.runId()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id = ? AND status = 'SUCCEEDED'",
                Integer.class, bytes(run.runId()))).isEqualTo(1);
    }

    @Test
    void recovery_afterActionCreationBeforeToolCommit_reusesThePendingAction() {
        DeliveryOrder order = createOrder();
        AgentPrincipal requester = principal("ops-admin-test", "OPS_ADMIN");
        AgentConversationService.RunSummary initial = createCancelRun(order, requester);
        UUID actionId = UUID.fromString(initial.actionId());
        Instant old = Instant.now(clock).minusSeconds(30);
        String checkpoint = "{\"phase\":\"TOOL_PENDING\",\"toolName\":\"request_cancel\","
                + "\"toolCallKey\":\"primary\",\"toolArguments\":{\"orderId\":\""
                + order.id() + "\",\"expectedVersion\":" + order.version()
                + ",\"reasonCode\":\"CUSTOMER_REQUEST\"}}";
        jdbc.update("UPDATE agent_run SET status = 'RUNNING', checkpoint_json = ?, updated_at = ? WHERE id = ?",
                checkpoint, Timestamp.from(old), bytes(initial.runId()));
        jdbc.update("UPDATE agent_tool_call SET status = 'RUNNING', result_json = NULL "
                        + "WHERE run_id = ? AND tool_call_key = 'primary'", bytes(initial.runId()));

        assertThat(agents.recoverStaleRuns()).isGreaterThanOrEqualTo(1);

        assertThat(agentRepository.findAction(actionId).orElseThrow().status())
                .isEqualTo(AgentActionStatus.PENDING_CONFIRMATION);
        assertThat(agents.summary(initial.runId(), requester).status())
                .isEqualTo(AgentRunStatus.WAITING_CONFIRMATION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_action WHERE run_id = ?", Integer.class,
                bytes(initial.runId()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_tool_call WHERE run_id = ? "
                + "AND tool_call_key = 'primary'", String.class, bytes(initial.runId())))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void events_streamWaitsForApprovalAndPushesResolvedEvents() throws Exception {
        DeliveryOrder order = createOrder();
        AgentPrincipal requester = principal("ops-admin-test", "OPS_ADMIN");
        AgentConversationService.RunSummary pending = createCancelRun(order, requester);
        UUID actionId = UUID.fromString(pending.actionId());
        AgentPrincipal approver = principal("approver-test", "OPS_APPROVER");

        MvcResult stream = mockMvc.perform(get("/api/v1/ops/runs/" + pending.runId() + "/events")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password"))
                        .header("Last-Event-ID", pending.runId() + ":" + pending.lastSequence()))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();

        agents.approve(actionId, approver, "agent-sse-approval");

        String pushed = mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(pushed).contains("event: message", "event: done")
                .contains("id: " + pending.runId() + ":" + (pending.lastSequence() + 1));
    }

    private AgentConversationService.RunSummary createCancelRun(DeliveryOrder order, AgentPrincipal requester) {
        return agents.chat(new AgentConversationService.ChatCommand("请取消订单", null, null, order.id(),
                order.version(), "CUSTOMER_REQUEST", null, null, null, null),
                "agent-action-" + UUID.randomUUID(), requester, "agent-test");
    }

    private AgentConversationService.RunSummary createReassignmentRun(DeliveryOrder order) {
        return agents.chat(new AgentConversationService.ChatCommand("请更换骑手", null, null, order.id(),
                order.version(), "COURIER_REQUEST", null, null, null, null),
                "agent-reassign-" + UUID.randomUUID(), principal("ops-admin-test", "OPS_ADMIN"), "agent-test");
    }

    private DeliveryOrder createOfferedOrder() {
        Instant now = Instant.now(clock);
        DeliveryOrder created = orders.create(new OrderApplicationService.CreateOrderCommand(
                DemoDataInitializer.DEMO_MERCHANT_ID, DemoDataInitializer.DEMO_REGION_ID,
                30.250, 120.160, 30.251, 120.161, now.minusSeconds(120), now.plusSeconds(7200)));
        dispatch.advanceCreatedOrder(created.id());
        var online = presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        presence.heartbeat(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID,
                online.presenceGeneration(), 1, 30.250, 120.160);
        DispatchService.DispatchDecision decision = dispatch.tryDispatch(created.id());
        assertThat(decision.outcome()).isEqualTo("OFFER_CREATED");
        return orders.get(created.id());
    }

    private int capacityCount(String column, UUID courierId) {
        if (!Set.of("committed_count", "reserved_count").contains(column)) {
            throw new IllegalArgumentException("unsupported capacity column");
        }
        return jdbc.queryForObject("SELECT " + column + " FROM courier_capacity WHERE courier_id = ?",
                Integer.class, bytes(courierId));
    }

    private DeliveryOrder createOrder() {
        return orders.create(new OrderApplicationService.CreateOrderCommand(DemoDataInitializer.DEMO_MERCHANT_ID,
                DemoDataInitializer.DEMO_REGION_ID, 30.250, 120.160, 30.251, 120.161,
                Instant.parse("2035-01-01T10:00:00Z"), Instant.parse("2035-01-01T11:00:00Z")));
    }

    private AgentPrincipal principal(String actor, String role) {
        return new AgentPrincipal(actor, Set.of(role), Set.of("demo-region"));
    }

    private int count(String sql, UUID orderId) {
        return jdbc.queryForObject(sql, Integer.class, bytes(orderId));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
