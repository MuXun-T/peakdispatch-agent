package com.peakdispatch.application.agent;

import java.util.List;

public record AgentIntentClassification(AgentIntent intent, double confidence, String rationale,
                                        List<String> missingFields) {
    public AgentIntentClassification {
        if (intent == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || rationale == null || missingFields == null) {
            throw new IllegalArgumentException("invalid intent classification");
        }
        missingFields = List.copyOf(missingFields);
    }
}
