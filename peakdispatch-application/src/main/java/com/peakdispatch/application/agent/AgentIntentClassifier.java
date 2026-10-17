package com.peakdispatch.application.agent;

import java.util.Map;

/** Pluggable intent classification boundary; implementations must not provide identity or authority data. */
public interface AgentIntentClassifier {
    AgentIntentClassification classify(String message);

    /** Returns a constrained tool plan when supported, or null for classification-only providers. */
    default AgentPlan plan(String message, Map<String, Object> suppliedSlots) {
        return null;
    }

    default String modelId() {
        return getClass().getSimpleName();
    }

    default String promptVersion() {
        return "unversioned";
    }
}
