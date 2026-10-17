package com.peakdispatch.application.agent;

/** Provider usage metadata safe to persist; it contains counts only, never model content. */
public record AgentTokenUsage(long inputTokens, long outputTokens) {
    public static final AgentTokenUsage NONE = new AgentTokenUsage(0, 0);

    public AgentTokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }

    public long totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }

    public boolean isZero() {
        return inputTokens == 0 && outputTokens == 0;
    }
}
