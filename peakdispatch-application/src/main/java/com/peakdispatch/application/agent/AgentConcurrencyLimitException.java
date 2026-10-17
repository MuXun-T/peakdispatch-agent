package com.peakdispatch.application.agent;

public class AgentConcurrencyLimitException extends RuntimeException {
    private final String code;
    private final long retryAfterSeconds;

    public AgentConcurrencyLimitException(String code, long retryAfterSeconds) {
        super(code);
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
