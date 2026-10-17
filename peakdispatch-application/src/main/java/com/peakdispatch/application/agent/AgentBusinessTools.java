package com.peakdispatch.application.agent;

import java.util.Map;
import java.util.UUID;

public interface AgentBusinessTools {
    String orderRegion(UUID orderId);

    String courierRegion(UUID courierId);

    Map<String, Object> invoke(String toolName, Map<String, Object> arguments,
                               String actorId, String traceId);
}
