package com.peakdispatch.application.agent;

import java.util.Map;

public record AgentPlan(AgentIntent intent, double confidence, boolean needsClarification,
                        String clarificationQuestion, String toolName, Map<String, Object> arguments,
                        AgentTokenUsage tokenUsage) {
    public AgentPlan(AgentIntent intent, double confidence, boolean needsClarification,
                     String clarificationQuestion, String toolName, Map<String, Object> arguments) {
        this(intent, confidence, needsClarification, clarificationQuestion, toolName, arguments,
                AgentTokenUsage.NONE);
    }

    public AgentPlan {
        if (intent == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || arguments == null) {
            throw new IllegalArgumentException("invalid Agent plan");
        }
        arguments = Map.copyOf(arguments);
        tokenUsage = tokenUsage == null ? AgentTokenUsage.NONE : tokenUsage;
    }

    public AgentIntentClassification classification() {
        return new AgentIntentClassification(intent, confidence, "STRUCTURED_MODEL_PLAN", java.util.List.of());
    }
}
