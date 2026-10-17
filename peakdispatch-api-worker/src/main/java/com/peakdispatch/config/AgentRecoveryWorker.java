package com.peakdispatch.config;

import com.peakdispatch.application.agent.AgentConversationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class AgentRecoveryWorker {
    private final AgentConversationService agents;

    public AgentRecoveryWorker(AgentConversationService agents) {
        this.agents = agents;
    }

    @Scheduled(fixedDelayString = "${peakdispatch.agent.recovery-delay-ms:5000}")
    public void recoverRunsAndExpireActions() {
        agents.recoverStaleRuns();
        agents.expireDueActions();
    }
}
