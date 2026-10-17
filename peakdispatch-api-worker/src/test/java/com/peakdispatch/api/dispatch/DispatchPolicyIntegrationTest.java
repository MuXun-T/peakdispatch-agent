package com.peakdispatch.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peakdispatch.application.dispatch.DispatchPolicyService;
import com.peakdispatch.application.dispatch.DispatchPolicyProvider;
import com.peakdispatch.domain.dispatch.DispatchPolicy;
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
class DispatchPolicyIntegrationTest {
    @Autowired DispatchPolicyService policies;
    @Autowired MockMvc mockMvc;

    @Test
    void policyLifecycle_persistsVersionsAndRollbackRestoresConfiguration() {
        String region = "policy-test-region";
        DispatchPolicyProvider.PolicySelection initial = policies.active(region);
        DispatchPolicy custom = new DispatchPolicy(0.70, 0.20, 0.10, 12, 75, 25,
                5, new long[] {5, 15, 30, 60});

        DispatchPolicyProvider.PolicySelection draft = policies.create(region, custom, "approver-test");
        assertThat(draft.version()).isGreaterThan(initial.version());
        DispatchPolicyProvider.PolicySelection active = policies.activate(draft.id(), "approver-test");
        assertThat(policies.active(region).id()).isEqualTo(active.id());
        assertPolicyEqual(custom, active.policy());

        DispatchPolicyProvider.PolicySelection rolledBack = policies.rollback(region, initial.version(), "approver-test");

        assertThat(rolledBack.version()).isGreaterThan(active.version());
        assertPolicyEqual(initial.policy(), rolledBack.policy());
        assertThat(policies.active(region).id()).isEqualTo(rolledBack.id());

        assertThat(policies.deactivate(rolledBack.id(), "approver-test").status()).isEqualTo("RETIRED");
        assertThatThrownBy(() -> policies.active(region))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active dispatch policy");
        assertThatThrownBy(() -> policies.deactivate(rolledBack.id(), "approver-test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only an active policy");
    }

    private static void assertPolicyEqual(DispatchPolicy expected, DispatchPolicy actual) {
        assertThat(actual.etaWeight()).isEqualTo(expected.etaWeight());
        assertThat(actual.loadWeight()).isEqualTo(expected.loadWeight());
        assertThat(actual.urgencyWeight()).isEqualTo(expected.urgencyWeight());
        assertThat(actual.maxPickupDistanceKm()).isEqualTo(expected.maxPickupDistanceKm());
        assertThat(actual.heartbeatTimeoutSeconds()).isEqualTo(expected.heartbeatTimeoutSeconds());
        assertThat(actual.offerTtlSeconds()).isEqualTo(expected.offerTtlSeconds());
        assertThat(actual.maxRounds()).isEqualTo(expected.maxRounds());
        assertThat(actual.retryBackoffSeconds()).containsExactly(expected.retryBackoffSeconds());
    }

    @Test
    void activatePolicy_outsideApproverRegion_isForbidden() throws Exception {
        DispatchPolicyProvider.PolicySelection draft = policies.create(
                "policy-other-region", DispatchPolicy.demo(), "approver-test");

        mockMvc.perform(post("/api/v1/dispatch/policies/" + draft.id() + "/activate")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password"))
                        .header("Idempotency-Key", "policy-activate-" + draft.id()))
                .andExpect(status().isForbidden());
    }
}
