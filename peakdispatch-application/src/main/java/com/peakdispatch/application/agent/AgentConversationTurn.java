package com.peakdispatch.application.agent;

import java.util.Map;
import java.util.Set;

public record AgentConversationTurn(String userMessage, String assistantMessage, AgentIntent intent,
                                   Map<String, Object> suppliedSlots, Set<String> missingFields,
                                   boolean pendingClarification) {
    public AgentConversationTurn {
        suppliedSlots = suppliedSlots == null ? Map.of() : Map.copyOf(suppliedSlots);
        missingFields = missingFields == null ? Set.of() : Set.copyOf(missingFields);
    }
}
