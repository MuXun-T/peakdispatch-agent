package com.peakdispatch.application.agent;

import java.util.Set;

public record AgentPrincipal(String actorId, Set<String> roles, Set<String> regionScopes) {
    public AgentPrincipal {
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("actorId is required");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        regionScopes = regionScopes == null ? Set.of() : Set.copyOf(regionScopes);
    }

    public boolean operationsReader() {
        return roles.contains("OPS_VIEWER") || operationsApprover();
    }

    public boolean operationsApprover() {
        return roles.contains("OPS_APPROVER") || roles.contains("OPS_ADMIN");
    }

    public boolean administrator() {
        return roles.contains("OPS_ADMIN");
    }

    public boolean canAccessRegion(String regionId) {
        return regionScopes.contains(regionId);
    }
}
