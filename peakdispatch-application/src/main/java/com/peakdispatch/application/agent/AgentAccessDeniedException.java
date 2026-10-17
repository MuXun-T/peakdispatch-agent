package com.peakdispatch.application.agent;

public class AgentAccessDeniedException extends RuntimeException {
    public AgentAccessDeniedException(String message) {
        super(message);
    }
}
