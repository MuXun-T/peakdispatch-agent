package com.peakdispatch.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.agent.AgentBusinessTools;
import com.peakdispatch.application.agent.AgentConversationService;
import com.peakdispatch.application.agent.AgentExecutionConcurrency;
import com.peakdispatch.application.agent.AgentIntent;
import com.peakdispatch.application.agent.AgentIntentClassifier;
import com.peakdispatch.application.agent.AgentPlan;
import com.peakdispatch.application.agent.AgentPrincipal;
import com.peakdispatch.application.agent.AgentRepository;
import com.peakdispatch.application.agent.AgentRunRecord;
import com.peakdispatch.application.agent.AgentToolGateway;
import com.peakdispatch.application.agent.AgentTokenUsage;
import com.peakdispatch.application.agent.DeterministicMockAgent;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.domain.agent.AgentRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

@ExtendWith(MockitoExtension.class)
class AgentConversationPlanningTest {
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Mock AgentRepository repository;
    @Mock AgentIntentClassifier classifier;
    @Mock AgentBusinessTools businessTools;
    @Mock OrderApplicationService orders;

    private AgentToolGateway gateway;
    private AgentRunRecord run;

    @BeforeEach
    void setUp() {
        gateway = new AgentToolGateway(repository, businessTools, new ObjectMapper(), Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        run = new AgentRunRecord(RUN_ID, UUID.randomUUID(), "operator",
                Set.of("OPS_APPROVER"), Set.of("demo-region"), AgentRunStatus.RUNNING, null,
                "区域指标", "{\"message\":\"区域指标\",\"regionId\":\"demo-region\"}",
                "input-hash", "{\"phase\":\"STARTED\"}", "test-model", 0,
                AgentTokenUsage.NONE,
                "trace-test", NOW, NOW, null);
        when(classifier.modelId()).thenReturn("test-model");
        when(classifier.promptVersion()).thenReturn("plan-test-v1");
        when(repository.createRun(any(), anyString(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(run);
        when(repository.findRun(RUN_ID)).thenReturn(Optional.of(run));
    }

    @Test
    void chat_whenPlanToolDoesNotMatchIntent_failsWithoutInvokingBusinessTool() {
        when(classifier.plan(anyString(), anyMap())).thenReturn(new AgentPlan(AgentIntent.REGION_METRICS,
                0.95, false, null, "diagnose_order", Map.of("orderId", UUID.randomUUID().toString())));

        service(Runnable::run).chat(command(), "plan-test-" + UUID.randomUUID(), principal(), "trace-test");

        verify(businessTools, never()).invoke(anyString(), anyMap(), anyString(), anyString());
        verify(repository, never()).beginToolCall(any(), anyString(), anyString(), anyString(), any());
        verify(repository).audit(eq("operator"), eq("AGENT_TOOL_CALL"), anyString(), anyString(),
                eq("DENIED"), anyString(), anyString(), any());
    }

    @Test
    void chat_whenRequiredRegionIsMissing_asksWithoutCallingTool() {
        when(classifier.plan(anyString(), anyMap())).thenReturn(new AgentPlan(AgentIntent.REGION_METRICS,
                0.95, false, null, "get_region_metrics", Map.of()));

        service(Runnable::run).chat(command(), "plan-test-" + UUID.randomUUID(), principal(), "trace-test");

        verify(repository).storeEvent(eq(RUN_ID), eq(AgentRunStatus.RUNNING), eq(AgentIntent.REGION_METRICS),
                anyMap(), eq("clarification_required"), eq("clarification_required"), anyMap(), any());
        verify(businessTools, never()).invoke(anyString(), anyMap(), anyString(), anyString());
        verify(repository, never()).beginToolCall(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void chat_whenConfidenceIsLow_asksWithoutCallingTool() {
        when(classifier.plan(anyString(), anyMap())).thenReturn(new AgentPlan(AgentIntent.REGION_METRICS,
                0.2, false, null, "get_region_metrics", Map.of("regionId", "demo-region"),
                new AgentTokenUsage(19, 7)));

        service(Runnable::run).chat(command(), "plan-test-" + UUID.randomUUID(), principal(), "trace-test");

        verify(repository).storeEvent(eq(RUN_ID), eq(AgentRunStatus.RUNNING), eq(AgentIntent.REGION_METRICS),
                anyMap(), eq("clarification_required"), eq("clarification_required"), anyMap(), any());
        verify(businessTools, never()).invoke(anyString(), anyMap(), anyString(), anyString());
        verify(repository, never()).beginToolCall(any(), anyString(), anyString(), anyString(), any());
        verify(repository).recordTokenUsage(eq(RUN_ID), eq(new AgentTokenUsage(19, 7)));
    }

    @Test
    void chat_sopAnswerRetainsCitationAndSourceFromToolEvidence() {
        String sourceUri = "https://knowledge.example.invalid/sop/dispatch-v3";
        Map<String, Object> citation = Map.of("citationId", "sop-citation-1", "title", "Dispatch SOP",
                "version", "3", "uri", sourceUri);
        Map<String, Object> evidence = Map.of("status", "EVIDENCE", "citations", java.util.List.of(citation));
        when(classifier.plan(anyString(), anyMap())).thenReturn(new AgentPlan(AgentIntent.SOP_QA,
                0.96, false, null, "search_sop", Map.of("query", "dispatch", "regionId", "demo-region",
                "category", "SOP")));
        UUID toolCallId = UUID.randomUUID();
        when(repository.beginToolCall(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new com.peakdispatch.application.agent.AgentToolCallRecord(toolCallId,
                        "search_sop", "args-hash", "RUNNING", null, null));
        when(businessTools.invoke(eq("search_sop"), anyMap(), eq("operator"), eq("trace-test")))
                .thenReturn(evidence);

        service(Runnable::run).chat(command(), "plan-test-" + UUID.randomUUID(), principal(), "trace-test");

        ArgumentCaptor<Map<String, Object>> citationPayload = ArgumentCaptor.forClass(Map.class);
        verify(repository).storeEvent(eq(RUN_ID), eq(AgentRunStatus.RUNNING), eq(AgentIntent.SOP_QA), anyMap(),
                eq("citation"), eq("citation:sop-citation-1"), citationPayload.capture(), any());
        assertThat(citationPayload.getValue()).containsEntry("uri", sourceUri).containsEntry("version", "3");
        ArgumentCaptor<Map<String, Object>> answerPayload = ArgumentCaptor.forClass(Map.class);
        verify(repository).storeEvent(eq(RUN_ID), eq(AgentRunStatus.COMPLETED), eq(AgentIntent.SOP_QA), anyMap(),
                eq("message"), eq("final_message"), answerPayload.capture(), any());
        assertThat(answerPayload.getValue().get("content").toString()).contains(sourceUri, "sop-citation-1", "版本 3");
    }

    @Test
    void startChat_returnsBeforeTheSubmittedRunExecutes() {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        TaskExecutor executor = submitted::set;

        AgentConversationService.RunSummary summary = service(executor).startChat(command(),
                "plan-test-" + UUID.randomUUID(), principal(), "trace-test");

        assertThat(summary.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(submitted.get()).isNotNull();
        verify(classifier, never()).plan(anyString(), anyMap());
        verify(classifier, never()).classify(anyString());
    }

    @Test
    void chatWithServerPlan_skipsModelAndPersistsPlanForRecovery() throws Exception {
        AgentPlan serverPlan = new AgentPlan(AgentIntent.REGION_METRICS,
                1.0, false, null, "get_region_metrics", Map.of());

        service(Runnable::run).chatWithServerPlan(command(), "plan-test-" + UUID.randomUUID(),
                principal(), "trace-test", serverPlan);

        verify(classifier, never()).plan(anyString(), anyMap());
        verify(classifier, never()).classify(anyString());
        verify(businessTools, never()).invoke(anyString(), anyMap(), anyString(), anyString());
        ArgumentCaptor<String> inputJson = ArgumentCaptor.forClass(String.class);
        verify(repository).createRun(any(), anyString(), any(), any(), anyString(), anyString(),
                inputJson.capture(), anyString(), anyString(), anyString(), anyString(), any());
        JsonNode stored = new ObjectMapper().readTree(inputJson.getValue());
        assertThat(stored.path("serverPlan").path("toolName").asText()).isEqualTo("get_region_metrics");
        assertThat(stored.path("serverPlan").path("intent").asText()).isEqualTo("REGION_METRICS");
    }

    private AgentConversationService service(TaskExecutor executor) {
        return new AgentConversationService(repository, gateway, classifier, new DeterministicMockAgent(), orders,
                new ObjectMapper(), Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                AgentExecutionConcurrency.noop(), null, executor);
    }

    private static AgentConversationService.ChatCommand command() {
        return new AgentConversationService.ChatCommand("区域指标", null, "demo-region", null,
                null, null, null, null, null, null);
    }

    private static AgentPrincipal principal() {
        return new AgentPrincipal("operator", Set.of("OPS_APPROVER"), Set.of("demo-region"));
    }
}
