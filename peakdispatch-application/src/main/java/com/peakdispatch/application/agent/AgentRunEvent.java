package com.peakdispatch.application.agent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentRunEvent(UUID runId, long sequence, String eventType, Map<String, Object> payload,
                            Instant createdAt) {
    public AgentRunEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
