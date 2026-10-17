package com.peakdispatch.domain.agent;

import java.util.EnumSet;
import java.util.Map;

public enum AgentActionStatus {
    PENDING_CONFIRMATION,
    CLAIMED,
    COMPLETED,
    FAILED,
    REJECTED,
    EXPIRED,
    INVALIDATED;

    private static final Map<AgentActionStatus, EnumSet<AgentActionStatus>> TRANSITIONS = Map.of(
            PENDING_CONFIRMATION, EnumSet.of(CLAIMED, REJECTED, EXPIRED, INVALIDATED),
            CLAIMED, EnumSet.of(COMPLETED, FAILED, INVALIDATED),
            COMPLETED, EnumSet.noneOf(AgentActionStatus.class),
            FAILED, EnumSet.noneOf(AgentActionStatus.class),
            REJECTED, EnumSet.noneOf(AgentActionStatus.class),
            EXPIRED, EnumSet.noneOf(AgentActionStatus.class),
            INVALIDATED, EnumSet.noneOf(AgentActionStatus.class));

    public boolean canTransitionTo(AgentActionStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED
                || this == EXPIRED || this == INVALIDATED;
    }
}
