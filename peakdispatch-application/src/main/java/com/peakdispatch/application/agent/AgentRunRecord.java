package com.peakdispatch.application.agent;

import com.peakdispatch.domain.agent.AgentRunStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AgentRunRecord(UUID runId, UUID sessionId, String ownerId, Set<String> roles, Set<String> regionScopes,
                             AgentRunStatus status, AgentIntent intent, String inputRedacted, String inputJson,
                             String inputHash, String checkpointJson, String modelId, long lastSequence,
                             AgentTokenUsage tokenUsage, String traceId, Instant startedAt, Instant updatedAt,
                             Instant endedAt) {
    public AgentRunRecord {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        regionScopes = regionScopes == null ? Set.of() : Set.copyOf(regionScopes);
        tokenUsage = tokenUsage == null ? AgentTokenUsage.NONE : tokenUsage;
    }
}
