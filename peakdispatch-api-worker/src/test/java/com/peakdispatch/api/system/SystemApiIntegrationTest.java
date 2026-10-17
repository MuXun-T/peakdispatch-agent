package com.peakdispatch.api.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void systemInfo_withApproverCredentials_returnsRuntimeAndRequestContext() throws Exception {
        mockMvc.perform(get("/api/v1/system/info")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password"))
                        .header("X-Request-Id", "test-request-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "test-request-123"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.runtimeRole").value("ALL"))
                .andExpect(jsonPath("$.data.dataMode").value("simulation"))
                .andExpect(jsonPath("$.data.authenticatedAs").value("approver-test"))
                .andExpect(jsonPath("$.requestId").value("test-request-123"));
    }

    @Test
    void systemInfo_withoutCredentials_returnsUnifiedAuthenticationError() throws Exception {
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void recoveryStatus_exposesDataModeAndRuntimeGuardrails() throws Exception {
        mockMvc.perform(get("/api/v1/system/recovery")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataMode").value("simulation"))
                .andExpect(jsonPath("$.data.redisEnvironment").value("demo"))
                .andExpect(jsonPath("$.data.rateLimitEnabled").isBoolean())
                .andExpect(jsonPath("$.data.javaVersion").isNotEmpty());
    }
}
