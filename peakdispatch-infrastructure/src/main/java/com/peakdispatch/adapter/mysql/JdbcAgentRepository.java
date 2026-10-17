package com.peakdispatch.adapter.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.agent.AgentActionRecord;
import com.peakdispatch.application.agent.AgentConversationTurn;
import com.peakdispatch.application.agent.AgentConflictException;
import com.peakdispatch.application.agent.AgentRepository;
import com.peakdispatch.application.agent.AgentRunEvent;
import com.peakdispatch.application.agent.AgentRunRecord;
import com.peakdispatch.application.agent.AgentToolCallRecord;
import com.peakdispatch.application.agent.AgentIntent;
import com.peakdispatch.application.agent.AgentTokenUsage;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.peakdispatch.domain.agent.AgentActionStatus;
import com.peakdispatch.domain.agent.AgentRunStatus;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentRepository implements AgentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAgentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public AgentRunRecord createRun(UUID requestedSessionId, String ownerId, Set<String> roles,
                                   Set<String> regionScopes, String requestKey, String inputRedacted,
                                   String inputJson, String inputHash, String traceId,
                                   String modelId, String promptVersion, Instant now) {
        Optional<AgentRunRecord> previous = findRunByRequestKey(ownerId, requestKey);
        if (previous.isPresent()) {
            if (!previous.get().inputHash().equals(inputHash)) {
                throw new AgentConflictException("Idempotency-Key was already used with different Agent input");
            }
            return previous.get();
        }

        UUID sessionId = requestedSessionId;
        Set<String> effectiveRoles = Set.copyOf(roles);
        Set<String> effectiveRegions = Set.copyOf(regionScopes);
        if (sessionId == null) {
            sessionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO agent_session (id, owner_id, region_scope, role_scope, summary_redacted,
                        created_at, updated_at, expires_at)
                    VALUES (?, ?, ?, ?, NULL, ?, ?, ?)
                    """, bytes(sessionId), ownerId, json(effectiveRegions), json(effectiveRoles), ts(now), ts(now),
                    ts(now.plusSeconds(30L * 24 * 60 * 60)));
        } else {
            SessionSnapshot session = jdbc.query("""
                    SELECT owner_id, region_scope, role_scope, expires_at FROM agent_session WHERE id = ? FOR UPDATE
                    """, rs -> rs.next() ? new SessionSnapshot(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getTimestamp(4).toInstant()) : null, bytes(sessionId));
            if (session == null || !ownerId.equals(session.ownerId()) || !session.expiresAt().isAfter(now)) {
                throw new AgentConflictException("Agent session is unavailable");
            }
            effectiveRoles = intersection(readSet(session.roles()), roles);
            effectiveRegions = intersection(readSet(session.regions()), regionScopes);
            jdbc.update("UPDATE agent_session SET role_scope = ?, region_scope = ?, updated_at = ? WHERE id = ?",
                    json(effectiveRoles), json(effectiveRegions), ts(now), bytes(sessionId));
        }

        UUID runId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO agent_run (id, session_id, owner_id, status, intent, checkpoint_json,
                        model_id, prompt_version, started_at, updated_at, ended_at, last_sequence,
                        token_usage_json, trace_id, client_request_key, input_redacted, input_json, input_hash)
                    VALUES (?, ?, ?, 'RUNNING', NULL, ?, ?, ?, ?, ?, NULL,
                        0, '{"input":0,"output":0}', ?, ?, ?, ?, ?)
                    """, bytes(runId), bytes(sessionId), ownerId, "{\"phase\":\"STARTED\"}", modelId,
                    promptVersion, ts(now), ts(now), traceId, requestKey, inputRedacted, inputJson, inputHash);
        } catch (DuplicateKeyException duplicate) {
            AgentRunRecord raced = findRunByRequestKey(ownerId, requestKey).orElseThrow(() -> duplicate);
            if (!raced.inputHash().equals(inputHash)) {
                throw new AgentConflictException("Idempotency-Key was already used with different Agent input");
            }
            return raced;
        }
        return findRun(runId).orElseThrow();
    }

    @Override
    public Optional<AgentRunRecord> findRun(UUID runId) {
        return jdbc.query("""
                SELECT r.id, r.session_id, r.owner_id, s.role_scope, s.region_scope, r.status, r.intent,
                    r.input_redacted, r.input_json, r.input_hash, r.checkpoint_json, r.model_id,
                    r.last_sequence, r.token_usage_json, r.trace_id, r.started_at, r.updated_at, r.ended_at
                FROM agent_run r JOIN agent_session s ON s.id = r.session_id WHERE r.id = ?
                """, rs -> rs.next() ? Optional.of(run(rs)) : Optional.empty(), bytes(runId));
    }

    @Override
    public Optional<AgentRunRecord> findRunByRequestKey(String ownerId, String requestKey) {
        return jdbc.query("""
                SELECT r.id, r.session_id, r.owner_id, s.role_scope, s.region_scope, r.status, r.intent,
                    r.input_redacted, r.input_json, r.input_hash, r.checkpoint_json, r.model_id,
                    r.last_sequence, r.token_usage_json, r.trace_id, r.started_at, r.updated_at, r.ended_at
                FROM agent_run r JOIN agent_session s ON s.id = r.session_id
                WHERE r.owner_id = ? AND r.client_request_key = ?
                """, rs -> rs.next() ? Optional.of(run(rs)) : Optional.empty(), ownerId, requestKey);
    }

    @Override
    public List<AgentConversationTurn> recentConversation(UUID sessionId, String ownerId, Set<String> roles,
                                                         Set<String> regionScopes, Instant now, int limit) {
        SessionSnapshot session = jdbc.query("""
                SELECT owner_id, region_scope, role_scope, expires_at FROM agent_session WHERE id = ?
                """, rs -> rs.next() ? new SessionSnapshot(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()) : null, bytes(sessionId));
        if (session == null || !ownerId.equals(session.ownerId()) || !session.expiresAt().isAfter(now)) {
            throw new AgentConflictException("Agent session is unavailable");
        }
        if (!roles.containsAll(readSet(session.roles())) || !regionScopes.containsAll(readSet(session.regions()))) {
            return List.of();
        }

        int boundedLimit = Math.max(1, Math.min(limit, 12));
        List<ConversationSnapshot> recent = jdbc.query("""
                SELECT id, intent, input_redacted, input_json, checkpoint_json
                FROM agent_run WHERE session_id = ? AND owner_id = ? AND status = 'COMPLETED'
                ORDER BY started_at DESC LIMIT ?
                """, (rs, row) -> new ConversationSnapshot(uuid(rs.getBytes("id")),
                rs.getString("intent"), rs.getString("input_redacted"), rs.getString("input_json"),
                rs.getString("checkpoint_json")), bytes(sessionId), ownerId, boundedLimit);

        List<AgentConversationTurn> turns = new ArrayList<>();
        for (int index = recent.size() - 1; index >= 0; index--) {
            ConversationSnapshot snapshot = recent.get(index);
            Map<String, Object> input = snapshot.inputJson() == null ? Map.of() : readMap(snapshot.inputJson());
            if (!snapshotAllowed(input, roles, regionScopes)) continue;
            String assistant = jdbc.query("""
                    SELECT payload_redacted FROM agent_run_event
                    WHERE run_id = ? AND event_type = 'message' ORDER BY sequence DESC LIMIT 1
                    """, rs -> rs.next() ? readMap(rs.getString(1)).get("content") instanceof String content
                    ? content : null : null, bytes(snapshot.runId()));
            if (snapshot.userMessage() == null || snapshot.userMessage().isBlank()
                    || assistant == null || assistant.isBlank()) continue;
            Map<String, Object> checkpoint = snapshot.checkpointJson() == null
                    ? Map.of() : readMap(snapshot.checkpointJson());
            Map<String, Object> slots = checkpoint.get("suppliedSlots") instanceof Map<?, ?> raw
                    ? stringKeyMap(raw) : Map.of();
            Set<String> missing = checkpoint.get("missingFields") instanceof List<?> list
                    ? list.stream().filter(String.class::isInstance).map(String.class::cast)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of();
            turns.add(new AgentConversationTurn(snapshot.userMessage(), assistant,
                    snapshot.intent() == null ? null : AgentIntent.valueOf(snapshot.intent()), slots,
                    missing, "CLARIFICATION".equals(checkpoint.get("phase"))));
        }
        return List.copyOf(turns);
    }

    @Override
    @Transactional
    public AgentRunEvent storeEvent(UUID runId, AgentRunStatus status, AgentIntent intent,
                                    Map<String, Object> checkpoint, String eventType, String dedupeKey,
                                    Map<String, Object> payload, Instant now) {
        RunState current = jdbc.query("SELECT status, last_sequence FROM agent_run WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? new RunState(AgentRunStatus.valueOf(rs.getString(1)), rs.getLong(2)) : null,
                bytes(runId));
        if (current == null) throw new AgentConflictException("Agent run no longer exists");
        if (current.status() != status && !current.status().canTransitionTo(status)) {
            throw new AgentConflictException("invalid AgentRun state transition");
        }

        if (dedupeKey != null) {
            Optional<AgentRunEvent> existing = eventByDedupe(runId, dedupeKey);
            if (existing.isPresent()) {
                updateRunState(runId, status, intent, checkpoint, current.sequence(), now);
                return existing.get();
            }
        }
        long sequence = current.sequence() + 1;
        Map<String, Object> completePayload = new LinkedHashMap<>();
        completePayload.put("runId", runId.toString());
        completePayload.put("sequence", sequence);
        completePayload.putAll(payload);
        String redactedPayload = SensitiveDataRedactor.redactJson(json(completePayload));
        jdbc.update("""
                INSERT INTO agent_run_event (run_id, sequence, event_type, payload_redacted, created_at, dedupe_key)
                VALUES (?, ?, ?, ?, ?, ?)
                """, bytes(runId), sequence, eventType, redactedPayload, ts(now), dedupeKey);
        updateRunState(runId, status, intent, checkpoint, sequence, now);
        return new AgentRunEvent(runId, sequence, eventType, readMap(redactedPayload), now);
    }

    @Override
    public List<AgentRunEvent> eventsAfter(UUID runId, long sequence, int limit) {
        return jdbc.query("""
                SELECT run_id, sequence, event_type, payload_redacted, created_at
                FROM agent_run_event WHERE run_id = ? AND sequence > ? ORDER BY sequence LIMIT ?
                """, (rs, row) -> event(rs), bytes(runId), sequence, limit);
    }

    @Override
    @Transactional
    public AgentToolCallRecord beginToolCall(UUID runId, String toolCallKey, String toolName,
                                             String argsHash, Instant now) {
        AgentToolCallRecord existing = jdbc.query("""
                SELECT id, tool_name, args_hash, status, result_json, error_code FROM agent_tool_call
                WHERE run_id = ? AND tool_call_key = ? FOR UPDATE
                """, rs -> rs.next() ? toolCall(rs) : null, bytes(runId), toolCallKey);
        if (existing != null) {
            if (!existing.argsHash().equals(argsHash) || !existing.toolName().equals(toolName)) {
                throw new AgentConflictException("toolCallKey was reused with different arguments");
            }
            if (!"SUCCEEDED".equals(existing.status())) {
                jdbc.update("UPDATE agent_tool_call SET status = 'RUNNING', error_code = NULL WHERE id = ?",
                        bytes(existing.id()));
                return new AgentToolCallRecord(existing.id(), toolName, argsHash, "RUNNING", null, null);
            }
            return existing;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_tool_call (id, run_id, tool_call_key, tool_name, args_hash, result_ref,
                    result_json, status, duration_ms, error_code, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, NULL, 'RUNNING', NULL, NULL, ?)
                """, bytes(id), bytes(runId), toolCallKey, toolName, argsHash, ts(now));
        return new AgentToolCallRecord(id, toolName, argsHash, "RUNNING", null, null);
    }

    @Override
    @Transactional
    public void recordTokenUsage(UUID runId, AgentTokenUsage usage) {
        if (usage == null || usage.isZero()) return;
        String currentJson = jdbc.queryForObject("SELECT token_usage_json FROM agent_run WHERE id = ? FOR UPDATE",
                String.class, bytes(runId));
        Map<String, Object> current = currentJson == null || currentJson.isBlank()
                ? new LinkedHashMap<>() : readMap(currentJson);
        long input = nonNegativeLong(current.get("input"));
        long output = nonNegativeLong(current.get("output"));
        current.put("input", Math.addExact(input, usage.inputTokens()));
        current.put("output", Math.addExact(output, usage.outputTokens()));
        current.put("total", Math.addExact(Math.addExact(input, usage.inputTokens()),
                Math.addExact(output, usage.outputTokens())));
        jdbc.update("UPDATE agent_run SET token_usage_json = ? WHERE id = ?", json(current), bytes(runId));
    }

    @Override
    public void completeToolCall(UUID toolCallId, String resultJson, long durationMs, Instant now) {
        jdbc.update("""
                UPDATE agent_tool_call SET status = 'SUCCEEDED', result_json = ?, result_ref = ?,
                    duration_ms = ?, error_code = NULL WHERE id = ?
                """, resultJson, "sha256:" + Integer.toHexString(resultJson.hashCode()), durationMs,
                bytes(toolCallId));
    }

    @Override
    public void failToolCall(UUID toolCallId, String errorCode, long durationMs, Instant now) {
        jdbc.update("""
                UPDATE agent_tool_call SET status = 'FAILED', error_code = ?, duration_ms = ? WHERE id = ?
                """, errorCode, durationMs, bytes(toolCallId));
    }

    @Override
    @Transactional
    public AgentActionRecord createAction(UUID runId, String actionType, UUID targetId, long expectedVersion,
                                          String paramsJson, String summaryRedacted, String paramsHash,
                                          String summaryHash, String requestedBy, Instant expiresAt,
                                          String idempotencyKey, Instant now) {
        AgentActionRecord existing = jdbc.query("SELECT * FROM agent_action WHERE idempotency_key = ? FOR UPDATE",
                rs -> rs.next() ? action(rs) : null, idempotencyKey);
        if (existing != null) {
            if (!existing.runId().equals(runId) || !existing.targetId().equals(targetId)
                    || !existing.paramsHash().equals(paramsHash) || !existing.summaryHash().equals(summaryHash)) {
                throw new AgentConflictException("Agent action key was reused with different content");
            }
            return existing;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_action (id, run_id, action_type, target_id, expected_version, params_json,
                    summary_redacted, params_hash, summary_hash, requested_by, confirmed_by, expires_at,
                    status, idempotency_key, result_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 'PENDING_CONFIRMATION', ?, NULL, ?, ?)
                """, bytes(id), bytes(runId), actionType, bytes(targetId), expectedVersion, paramsJson,
                summaryRedacted, paramsHash, summaryHash, requestedBy, ts(expiresAt), idempotencyKey,
                ts(now), ts(now));
        return findAction(id).orElseThrow();
    }

    @Override
    public Optional<AgentActionRecord> findAction(UUID actionId) {
        return jdbc.query("SELECT * FROM agent_action WHERE id = ?", rs ->
                rs.next() ? Optional.of(action(rs)) : Optional.empty(), bytes(actionId));
    }

    @Override
    public Optional<AgentActionRecord> lockAction(UUID actionId) {
        return jdbc.query("SELECT * FROM agent_action WHERE id = ? FOR UPDATE", rs ->
                rs.next() ? Optional.of(action(rs)) : Optional.empty(), bytes(actionId));
    }

    @Override
    public boolean claimAction(UUID actionId, String approverId, Instant now) {
        return jdbc.update("""
                UPDATE agent_action SET status = 'CLAIMED', confirmed_by = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING_CONFIRMATION'
                """, approverId, ts(now), bytes(actionId)) == 1;
    }

    @Override
    @Transactional
    public void resolveAction(AgentActionRecord action, AgentActionStatus status, String actorId,
                              String resultRef, String safeMessage, String traceId, Instant now) {
        if (!action.status().canTransitionTo(status)) {
            AgentActionRecord current = findAction(action.actionId()).orElseThrow();
            if (current.status() == status) return;
            throw new AgentConflictException("invalid AgentAction state transition");
        }
        int changed = jdbc.update("""
                UPDATE agent_action SET status = ?, confirmed_by = ?, result_ref = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """, status.name(), actorId, resultRef, ts(now), bytes(action.actionId()), action.status().name());
        if (changed != 1) throw new AgentConflictException("Agent action was concurrently resolved");

        String runStatus = jdbc.queryForObject("SELECT status FROM agent_run WHERE id = ?",
                String.class, bytes(action.runId()));
        AgentRunStatus currentRun = AgentRunStatus.valueOf(runStatus);
        if (currentRun != AgentRunStatus.COMPLETED && !currentRun.canTransitionTo(AgentRunStatus.COMPLETED)) {
            throw new AgentConflictException("waiting AgentRun cannot be resumed");
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", "COMPLETED");
        checkpoint.put("actionId", action.actionId().toString());
        checkpoint.put("actionOutcome", status.name());
        checkpoint.put("answer", safeMessage);
        storeEvent(action.runId(), AgentRunStatus.COMPLETED, null, checkpoint, "message", "action_message",
                Map.of("channel", "assistant", "content", safeMessage), now);
        storeEvent(action.runId(), AgentRunStatus.COMPLETED, null, checkpoint, "done", "done",
                Map.of("status", AgentRunStatus.COMPLETED.name(), "actionOutcome", status.name()), now);

        Map<String, Object> event = Map.of("actionId", action.actionId().toString(),
                "runId", action.runId().toString(), "outcome", status.name(),
                "resultRef", resultRef == null ? "" : resultRef);
        jdbc.update("""
                INSERT INTO outbox_event (event_id, topic, aggregate_type, aggregate_id, aggregate_version,
                    dedupe_key, payload_json, created_at, next_attempt_at)
                VALUES (?, 'agent.action.resolved', 'AGENT_ACTION', ?, 1, ?, ?, ?, ?)
                """, bytes(UUID.randomUUID()), bytes(action.actionId()),
                "agent-action:" + action.actionId() + ":" + status.name(), json(event), ts(now), ts(now));
        audit(actorId, "AGENT_ACTION_" + status.name(), "AGENT_ACTION", action.actionId().toString(),
                status.name(), "runId=" + action.runId() + ";targetId=" + action.targetId()
                        + ";expectedVersion=" + action.expectedVersion(), traceId, now);
    }

    @Override
    public List<UUID> expiredActionIds(Instant now, int limit) {
        return jdbc.query("""
                SELECT id FROM agent_action WHERE status = 'PENDING_CONFIRMATION' AND expires_at <= ?
                ORDER BY expires_at LIMIT ?
                """, (rs, row) -> uuid(rs.getBytes(1)), ts(now), limit);
    }

    @Override
    public List<UUID> recoverableRunIds(Instant olderThan, int limit) {
        return jdbc.query("""
                SELECT id FROM agent_run WHERE status = 'RUNNING' AND updated_at <= ?
                ORDER BY updated_at LIMIT ?
                """, (rs, row) -> uuid(rs.getBytes(1)), ts(olderThan), limit);
    }

    @Override
    public void audit(String actorId, String action, String targetType, String targetId,
                      String decision, String details, String traceId, Instant now) {
        jdbc.update("""
                INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision,
                    trace_id, details_redacted, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(UUID.randomUUID()), actorId, action, targetType, targetId, decision,
                traceId, SensitiveDataRedactor.redact(details), ts(now));
    }

    private void updateRunState(UUID runId, AgentRunStatus status, AgentIntent intent,
                                Map<String, Object> checkpoint, long sequence, Instant now) {
        jdbc.update("""
                UPDATE agent_run SET status = ?, intent = COALESCE(?, intent), checkpoint_json = ?,
                    last_sequence = ?, updated_at = ?, ended_at = CASE WHEN ? IN ('COMPLETED','FAILED','CANCELLED')
                        THEN ? ELSE ended_at END
                WHERE id = ?
                """, status.name(), intent == null ? null : intent.name(), json(checkpoint), sequence,
                ts(now), status.name(), ts(now), bytes(runId));
    }

    private Optional<AgentRunEvent> eventByDedupe(UUID runId, String key) {
        return jdbc.query("""
                SELECT run_id, sequence, event_type, payload_redacted, created_at
                FROM agent_run_event WHERE run_id = ? AND dedupe_key = ?
                """, rs -> rs.next() ? Optional.of(event(rs)) : Optional.empty(), bytes(runId), key);
    }

    private AgentRunRecord run(ResultSet rs) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        return new AgentRunRecord(uuid(rs.getBytes("id")), uuid(rs.getBytes("session_id")),
                rs.getString("owner_id"), readSet(rs.getString("role_scope")),
                readSet(rs.getString("region_scope")), AgentRunStatus.valueOf(rs.getString("status")),
                rs.getString("intent") == null ? null : AgentIntent.valueOf(rs.getString("intent")),
                rs.getString("input_redacted"), rs.getString("input_json"), rs.getString("input_hash"),
                rs.getString("checkpoint_json"), rs.getString("model_id"), rs.getLong("last_sequence"),
                tokenUsage(rs.getString("token_usage_json")), rs.getString("trace_id"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), ended == null ? null : ended.toInstant());
    }

    private AgentTokenUsage tokenUsage(String value) {
        if (value == null || value.isBlank()) return AgentTokenUsage.NONE;
        Map<String, Object> usage = readMap(value);
        return new AgentTokenUsage(nonNegativeLong(usage.get("input")),
                nonNegativeLong(usage.get("output")));
    }

    private AgentRunEvent event(ResultSet rs) throws SQLException {
        return new AgentRunEvent(uuid(rs.getBytes("run_id")), rs.getLong("sequence"),
                rs.getString("event_type"), readMap(rs.getString("payload_redacted")),
                rs.getTimestamp("created_at").toInstant());
    }

    private AgentToolCallRecord toolCall(ResultSet rs) throws SQLException {
        return new AgentToolCallRecord(uuid(rs.getBytes("id")), rs.getString("tool_name"),
                rs.getString("args_hash"), rs.getString("status"), rs.getString("result_json"),
                rs.getString("error_code"));
    }

    private AgentActionRecord action(ResultSet rs) throws SQLException {
        return new AgentActionRecord(uuid(rs.getBytes("id")), uuid(rs.getBytes("run_id")),
                rs.getString("action_type"), uuid(rs.getBytes("target_id")), rs.getLong("expected_version"),
                rs.getString("params_json"), rs.getString("summary_redacted"), rs.getString("params_hash"),
                rs.getString("summary_hash"), rs.getString("requested_by"), rs.getString("confirmed_by"),
                rs.getTimestamp("expires_at").toInstant(), AgentActionStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"), rs.getString("result_ref"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private Map<String, Object> readMap(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("invalid Agent JSON data", exception);
        }
    }

    private static long nonNegativeLong(Object value) {
        if (value instanceof Number number && number.longValue() >= 0) return number.longValue();
        return 0;
    }

    private Set<String> readSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("invalid Agent scope data", exception);
        }
    }

    private boolean snapshotAllowed(Map<String, Object> input, Set<String> roles, Set<String> regions) {
        Set<String> savedRoles = stringSet(input.get("_principalRoles"));
        Set<String> savedRegions = stringSet(input.get("_principalRegions"));
        return !savedRoles.isEmpty() && !savedRegions.isEmpty()
                && roles.containsAll(savedRoles) && regions.containsAll(savedRegions);
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> list)) return Set.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("could not serialize Agent JSON data", exception);
        }
    }

    private static Set<String> intersection(Set<String> first, Set<String> second) {
        java.util.HashSet<String> result = new java.util.HashSet<>(first);
        result.retainAll(second);
        return Set.copyOf(result);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private record SessionSnapshot(String ownerId, String regions, String roles, Instant expiresAt) {
    }

    private record ConversationSnapshot(UUID runId, String intent, String userMessage, String inputJson,
                                        String checkpointJson) {
    }

    private record RunState(AgentRunStatus status, long sequence) {
    }
}
