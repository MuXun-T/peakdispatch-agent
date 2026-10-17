package com.peakdispatch.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IdempotencyIntegrationTest.ProbeConfiguration.class)
class IdempotencyIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProbeController probe;

    @BeforeEach
    void resetProbe() {
        probe.reset();
    }

    @Test
    void post_withSameKeyAndEquivalentJson_returnsOriginalResponseWithoutRepeatingWork() throws Exception {
        var approver = SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password");

        var first = mockMvc.perform(post("/api/v1/test/idempotency")
                        .with(approver)
                        .header("Idempotency-Key", "idem-test-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":1,\"b\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        var repeated = mockMvc.perform(post("/api/v1/test/idempotency")
                        .with(approver)
                        .header("Idempotency-Key", "idem-test-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"b\": 2, \"a\": 1 }"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(repeated).isEqualTo(first);
        assertThat(probe.invocations()).isEqualTo(1);
    }

    @Test
    void post_withSameKeyAndDifferentBody_returnsConflict() throws Exception {
        var approver = SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password");
        mockMvc.perform(post("/api/v1/test/idempotency")
                        .with(approver)
                        .header("Idempotency-Key", "idem-test-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/test/idempotency")
                        .with(approver)
                        .header("Idempotency-Key", "idem-test-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(probe.invocations()).isEqualTo(1);
    }

    @Test
    void post_withoutKey_isRejectedBeforeCallingController() throws Exception {
        mockMvc.perform(post("/api/v1/test/idempotency")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(probe.invocations()).isZero();
    }

    @Test
    void post_withMerchantCredentials_cannotUseApproverOnlyOperation() throws Exception {
        mockMvc.perform(post("/api/v1/test/idempotency")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant-test", "merchant-test-password"))
                        .header("Idempotency-Key", "idem-role-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        assertThat(probe.invocations()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    @RequestMapping("/api/v1/test/idempotency")
    static class ProbeController {
        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping
        @PreAuthorize("hasRole('OPS_APPROVER')")
        ResponseEntity<Map<String, Object>> post(@RequestBody Map<String, Object> body) {
            return ResponseEntity.status(201).body(Map.of("body", body, "execution", calls.incrementAndGet()));
        }

        int invocations() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
