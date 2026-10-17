package com.peakdispatch.domain.agent;

import java.util.EnumSet;
import java.util.Map;

public enum AgentRunStatus {
    RUNNING,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Map<AgentRunStatus, EnumSet<AgentRunStatus>> TRANSITIONS = Map.of(
            RUNNING, EnumSet.of(WAITING_CONFIRMATION, COMPLETED, FAILED, CANCELLED),
            WAITING_CONFIRMATION, EnumSet.of(RUNNING, COMPLETED, FAILED, CANCELLED),
            COMPLETED, EnumSet.noneOf(AgentRunStatus.class),
            FAILED, EnumSet.noneOf(AgentRunStatus.class),
            CANCELLED, EnumSet.noneOf(AgentRunStatus.class));

    public boolean canTransitionTo(AgentRunStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
