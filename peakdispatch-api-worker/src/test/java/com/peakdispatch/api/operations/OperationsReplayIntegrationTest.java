package com.peakdispatch.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.operations.SimulationReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationsReplayIntegrationTest {
    @Autowired SimulationReplayService replays;
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void replay_withFixedSeedProducesSameReportAndDifferentSeedChangesHash() {
        var request = new SimulationReplayService.ReplayRequest("replay-test-region", 87321L,
                "weekday-lunch", 60, 24, null);

        var first = replays.replay(request, "replay-test-actor");
        var repeated = replays.replay(request, "replay-test-actor");
        var changed = replays.replay(new SimulationReplayService.ReplayRequest("replay-test-region", 87322L,
                "weekday-lunch", 60, 24, first.policyVersion()), "replay-test-actor");

        assertThat(first.report()).isEqualTo(repeated.report());
        assertThat(first.resultHash()).isEqualTo(repeated.resultHash());
        assertThat(changed.resultHash()).isNotEqualTo(first.resultHash());
        assertThat(replays.compare(first.replayId(), repeated.replayId(), "replay-test-actor", "trace-replay")
                .identical()).isTrue();
    }

    @Test
    void replayEndpoint_reusesIdempotencyKeyAndRejectsCourierRole() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM operations_replay WHERE created_by = ?",
                Integer.class, "approver-test");
        String body = """
                {"regionId":"demo-region","seed":19283,"scenarioId":"weekend","orderCount":12,
                 "courierCount":8}
                """;
        var request = post("/api/v1/ops/simulations/replays")
                .with(SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password"))
                .header("Idempotency-Key", "replay-api-once-19283")
                .contentType("application/json").content(body);
        MvcResult first = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        MvcResult repeated = mockMvc.perform(post("/api/v1/ops/simulations/replays")
                .with(SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password"))
                .header("Idempotency-Key", "replay-api-once-19283")
                .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsString());
        JsonNode repeatedJson = mapper.readTree(repeated.getResponse().getContentAsString());

        assertThat(firstJson.path("data").path("replayId").asText())
                .isEqualTo(repeatedJson.path("data").path("replayId").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operations_replay WHERE created_by = ?",
                Integer.class, "approver-test") - before).isEqualTo(1);

        mockMvc.perform(post("/api/v1/ops/simulations/replays")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("courier-test", "courier-test-password"))
                        .header("Idempotency-Key", "replay-courier-denied")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                WHERE actor_id = 'courier-test' AND action = 'OPS_ACCESS_DENIED'
                    AND target_id = '/api/v1/ops/simulations/replays'
                """, Integer.class)).isGreaterThan(0);
    }

    @Test
    void dashboard_enforcesRegionScopeAndAuditsBothAllowedAndDeniedReads() throws Exception {
        String range = "&from=2026-10-11T00:00:00Z&to=2026-10-12T00:00:00Z";
        mockMvc.perform(get("/api/v1/ops/dashboard?regionId=test-other-region" + range)
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("ops-viewer-test", "ops-viewer-test-password")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id = 'ops-viewer-test'
                    AND action = 'OPS_DASHBOARD_READ' AND decision = 'ALLOWED'
                """, Integer.class)).isGreaterThan(0);

        mockMvc.perform(get("/api/v1/ops/dashboard?regionId=blocked-region" + range)
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("ops-viewer-test", "ops-viewer-test-password")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id = 'ops-viewer-test'
                    AND decision = 'DENIED' AND target_id LIKE '%blocked-region%'
                """, Integer.class)).isGreaterThan(0);
    }
}
