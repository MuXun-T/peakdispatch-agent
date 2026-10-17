package com.peakdispatch.application.agent;

import java.util.Optional;

public interface AgentExecutionConcurrency {
    Lease acquireRun(String actorId);

    Lease acquireModel(String modelId);

    default Optional<Lease> tryAcquireExecution(String runId) {
        return Optional.of(acquireRun(runId));
    }

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    static AgentExecutionConcurrency noop() {
        return new AgentExecutionConcurrency() {
            @Override public Lease acquireRun(String actorId) { return () -> { }; }
            @Override public Lease acquireModel(String modelId) { return () -> { }; }
            @Override public Optional<Lease> tryAcquireExecution(String runId) { return Optional.of(() -> { }); }
        };
    }
}
