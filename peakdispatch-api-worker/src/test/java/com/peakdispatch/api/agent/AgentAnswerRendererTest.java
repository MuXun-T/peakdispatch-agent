package com.peakdispatch.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.agent.AgentAnswerRenderer;
import com.peakdispatch.application.agent.AgentIntent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentAnswerRendererTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingToolEvidenceFallsBackWithoutInventingFacts() {
        String answer = AgentAnswerRenderer.render(AgentIntent.REGION_METRICS,
                Map.of("regionId", "demo-region", "from", "2026-10-15T00:00:00Z",
                        "to", "2026-10-15T01:00:00Z", "asOf", "2026-10-15T01:00:00Z",
                        "definition", "test", "metrics", Map.of("waitingDispatch", 2)), mapper);

        assertThat(answer).isEqualTo("工具结果缺少可验证证据，无法生成该结论，请重新查询。");
        assertThat(answer).doesNotContain("在线骑手", "派单失败", "null");
    }

    @Test
    void dispatchAnswerIncludesOnlyEvidenceAndSourceFromToolResult() {
        String answer = AgentAnswerRenderer.render(AgentIntent.DISPATCH_EXPLANATION,
                Map.of("explanation", "时间线记录可确认", "dispatchEvidence", List.of(Map.of("type", "OFFER")),
                        "source", "mysql_dispatch_timeline"), mapper);

        assertThat(answer).contains("时间线记录可确认", "派单证据条数：1", "mysql_dispatch_timeline");
    }

    @Test
    void diagnosisRequiresEveryReasonToReferenceReturnedEvidence() {
        String answer = AgentAnswerRenderer.render(AgentIntent.ORDER_DIAGNOSIS,
                Map.of("orderId", "order-1", "status", "WAITING_DISPATCH", "reasonCodes", List.of("OFFER_TIMED_OUT"),
                        "reasonEvidence", Map.of("OFFER_TIMED_OUT", List.of("attempt:missing")),
                        "evidence", List.of(Map.of("evidenceId", "offer:1")), "unknowns", List.of()), mapper);

        assertThat(answer).isEqualTo("工具结果缺少可验证证据，无法生成该结论，请重新查询。");
        assertThat(answer).doesNotContain("OFFER_TIMED_OUT");
    }

    @Test
    void diagnosisRendersOnlyEvidenceBoundReasonsAndSourceTiming() {
        String answer = AgentAnswerRenderer.render(AgentIntent.ORDER_DIAGNOSIS,
                Map.of("orderId", "order-1", "status", "WAITING_DISPATCH", "prepEta", "2026-10-15T10:00:00Z",
                        "deadline", "2026-10-15T11:00:00Z", "reasonCodes", List.of("OFFER_TIMED_OUT"),
                        "reasonEvidence", Map.of("OFFER_TIMED_OUT", List.of("attempt:1")),
                        "evidence", List.of(Map.of("evidenceId", "attempt:1")), "unknowns", List.of()), mapper);

        assertThat(answer).contains("OFFER_TIMED_OUT", "原因证据绑定数 1", "attempt:1",
                "商家 ETA 2026-10-15T10:00:00Z", "订单截止 2026-10-15T11:00:00Z");
    }

    @Test
    void simulationAnswerRequiresTheToolReadOnlyMarker() {
        String answer = AgentAnswerRenderer.render(AgentIntent.DISPATCH_SIMULATION,
                Map.of("limitation", "仅使用历史证据", "dispatchEvidence", List.of(),
                        "mutatesDispatch", true), mapper);

        assertThat(answer).isEqualTo("工具结果缺少可验证证据，无法生成该结论，请重新查询。");
    }

    @Test
    void malformedCitationDowngradesInsteadOfClaimingSopEvidence() {
        String answer = AgentAnswerRenderer.render(AgentIntent.SOP_QA,
                Map.of("status", "EVIDENCE", "citations", List.of(Map.of("citationId", "c1",
                        "title", "SOP", "version", "3"))), mapper);

        assertThat(answer).isEqualTo("工具结果缺少可验证证据，无法生成该结论，请重新查询。");
        assertThat(answer).doesNotContain("SOP", "c1");
    }

    @Test
    void citationWithUnsupportedUriSchemeIsRejected() {
        String answer = AgentAnswerRenderer.render(AgentIntent.SOP_QA,
                Map.of("status", "EVIDENCE", "citations", List.of(Map.of("citationId", "c1",
                        "title", "SOP", "version", "3", "uri", "javascript:alert(1)"))), mapper);

        assertThat(answer).isEqualTo("工具结果缺少可验证证据，无法生成该结论，请重新查询。");
    }

    @Test
    void simulationAnswerSummarizesCandidateEtaAndHardConstraints() {
        String answer = AgentAnswerRenderer.render(AgentIntent.DISPATCH_SIMULATION,
                Map.of("mutatesDispatch", false, "regionId", "demo-region", "orderIds", List.of("order-1"),
                        "candidateCount", 1, "candidatePoolTruncated", false, "inputVersions", Map.of("order-1", 3),
                        "constraints", List.of("CAPACITY_EXCEEDED"), "limitation", "read only",
                        "candidates", List.of(Map.of("courierId", "courier-1", "executable", false,
                                "failureReasons", List.of("CAPACITY_EXCEEDED"),
                                "estimatedTimes", List.of(Map.of("orderId", "order-1",
                                        "estimatedDeliveredAt", "2026-10-15T10:30:00Z"))))), mapper);

        assertThat(answer).contains("demo-region", "order-1", "CAPACITY_EXCEEDED", "courier-1",
                "2026-10-15T10:30:00Z");
    }

    @Test
    void sopAnswerUsesRetrievedExcerptAsItsAnswerAndKeepsCitationSource() {
        String answer = AgentAnswerRenderer.render(AgentIntent.SOP_QA,
                Map.of("status", "EVIDENCE", "citations", List.of(Map.of("citationId", "c1",
                        "title", "Dispatch SOP", "version", "3", "section", "Timeout handling",
                        "excerpt", "When no courier accepts an offer, inspect capacity and heartbeat evidence.",
                        "uri", "https://example.invalid/sop"))), mapper);

        assertThat(answer).contains("inspect capacity and heartbeat evidence", "Timeout handling",
                "https://example.invalid/sop", "c1");
    }

    @Test
    void validCitationPreservesIdentityAndSource() {
        String answer = AgentAnswerRenderer.render(AgentIntent.SOP_QA,
                Map.of("status", "EVIDENCE", "citations", List.of(Map.of("citationId", "c1",
                        "title", "Dispatch SOP", "version", "3", "uri", "https://example.invalid/sop"))), mapper);

        assertThat(answer).contains("Dispatch SOP", "版本 3", "https://example.invalid/sop", "c1");
    }
}
