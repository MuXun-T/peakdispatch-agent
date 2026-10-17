package com.peakdispatch.application.agent;

import com.peakdispatch.domain.agent.AgentActionStatus;
import java.time.Instant;
import java.util.UUID;

public record AgentActionRecord(UUID actionId, UUID runId, String actionType, UUID targetId,
                                long expectedVersion, String paramsJson, String summaryRedacted,
                                String paramsHash, String summaryHash, String requestedBy,
                                String confirmedBy, Instant expiresAt, AgentActionStatus status,
                                String idempotencyKey, String resultRef, Instant createdAt, Instant updatedAt) {
}
