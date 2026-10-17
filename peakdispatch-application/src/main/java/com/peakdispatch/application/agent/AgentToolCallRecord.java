package com.peakdispatch.application.agent;

import java.util.UUID;

public record AgentToolCallRecord(UUID id, String toolName, String argsHash, String status,
                                  String resultJson, String errorCode) {
}
