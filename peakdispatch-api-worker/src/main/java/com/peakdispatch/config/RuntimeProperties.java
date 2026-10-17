package com.peakdispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peakdispatch.runtime")
public record RuntimeProperties(String role, String dataMode) {
    public RuntimeProperties {
        if (role == null || !role.matches("API|WORKER|ALL")) {
            throw new IllegalArgumentException("peakdispatch.runtime.role must be API, WORKER, or ALL");
        }
        if (dataMode == null || !dataMode.matches("simulation|production")) {
            throw new IllegalArgumentException("peakdispatch.runtime.data-mode must be simulation or production");
        }
    }
}
