package com.peakdispatch.application.agent;

public class AgentConcurrencyUnavailableException extends RuntimeException {
    public AgentConcurrencyUnavailableException(Throwable cause) {
        super("Agent concurrency control is unavailable", cause);
    }
}
