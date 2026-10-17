package com.peakdispatch.application.agent;

import com.peakdispatch.domain.agent.AgentActionStatus;
import com.peakdispatch.domain.agent.AgentRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AgentRepository {
    AgentRunRecord createRun(UUID requestedSessionId, String ownerId, Set<String> roles, Set<String> regionScopes,
                             String requestKey, String inputRedacted, String inputJson, String inputHash,
                             String traceId, String modelId, String promptVersion, Instant now);

    Optional<AgentRunRecord> findRun(UUID runId);

    Optional<AgentRunRecord> findRunByRequestKey(String ownerId, String requestKey);

    List<AgentConversationTurn> recentConversation(UUID sessionId, String ownerId, Set<String> roles,
                                                  Set<String> regionScopes, Instant now, int limit);

    AgentRunEvent storeEvent(UUID runId, AgentRunStatus status, AgentIntent intent,
                             Map<String, Object> checkpoint, String eventType, String dedupeKey,
                             Map<String, Object> payload, Instant now);

    List<AgentRunEvent> eventsAfter(UUID runId, long sequence, int limit);

    AgentToolCallRecord beginToolCall(UUID runId, String toolCallKey, String toolName,
                                      String argsHash, Instant now);

    /** Adds provider token counts to the run without persisting model input or output. */
    default void recordTokenUsage(UUID runId, AgentTokenUsage usage) {
        // Repositories that do not persist run state may intentionally ignore usage metadata.
    }

    void completeToolCall(UUID toolCallId, String resultJson, long durationMs, Instant now);

    void failToolCall(UUID toolCallId, String errorCode, long durationMs, Instant now);

    AgentActionRecord createAction(UUID runId, String actionType, UUID targetId, long expectedVersion,
                                   String paramsJson, String summaryRedacted, String paramsHash,
                                   String summaryHash, String requestedBy, Instant expiresAt,
                                   String idempotencyKey, Instant now);

    Optional<AgentActionRecord> findAction(UUID actionId);

    Optional<AgentActionRecord> lockAction(UUID actionId);

    boolean claimAction(UUID actionId, String approverId, Instant now);

    void resolveAction(AgentActionRecord action, AgentActionStatus status, String actorId,
                       String resultRef, String safeMessage, String traceId, Instant now);

    List<UUID> expiredActionIds(Instant now, int limit);

    List<UUID> recoverableRunIds(Instant olderThan, int limit);

    void audit(String actorId, String action, String targetType, String targetId,
               String decision, String details, String traceId, Instant now);
}
