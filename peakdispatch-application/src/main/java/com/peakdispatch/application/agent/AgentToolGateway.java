package com.peakdispatch.application.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.domain.agent.AgentActionStatus;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AgentToolGateway {
    private static final Duration ACTION_TTL = Duration.ofMinutes(15);
    private static final Set<String> READ_TOOLS = Set.of("get_region_metrics", "get_order_timeline",
            "diagnose_order", "explain_dispatch", "simulate_dispatch", "search_sop", "compare_peak_plans");
    private final AgentRepository repository;
    private final AgentBusinessTools businessTools;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PeakDispatchMetrics metrics;

    public AgentToolGateway(AgentRepository repository, AgentBusinessTools businessTools,
                            ObjectMapper mapper, Clock clock) {
        this(repository, businessTools, mapper, clock, null);
    }

    @Autowired
    public AgentToolGateway(AgentRepository repository, AgentBusinessTools businessTools,
                            ObjectMapper mapper, Clock clock, PeakDispatchMetrics metrics) {
        this.repository = repository;
        this.businessTools = businessTools;
        this.mapper = mapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Map<String, Object> invoke(UUID runId, AgentIntent intent, String toolCallKey, String toolName,
                                      Map<String, Object> supplied, AgentPrincipal principal, String traceId) {
        Instant started = Instant.now(clock);
        Map<String, Object> args;
        try {
            args = validate(intent, toolName, supplied, principal);
        } catch (RuntimeException exception) {
            repository.audit(principal.actorId(), "AGENT_TOOL_CALL", "AGENT_TOOL", safeToolName(toolName),
                    "DENIED", "runId=" + runId + ";reason=" + exception.getClass().getSimpleName(),
                    traceId, started);
            throw exception;
        }

        String argsJson = json(args);
        String argsHash = sha256(argsJson);
        AgentToolCallRecord call = repository.beginToolCall(runId, toolCallKey, toolName, argsHash, started);
        if ("SUCCEEDED".equals(call.status())) {
            repository.audit(principal.actorId(), "AGENT_TOOL_CALL", "AGENT_TOOL", toolName,
                    "ALLOWED", "runId=" + runId + ";toolCallKey=" + toolCallKey
                            + ";replayed=true;argsHash=" + argsHash, traceId, started);
            return readResult(call.resultJson());
        }

        try {
            Map<String, Object> result = READ_TOOLS.contains(toolName)
                    ? businessTools.invoke(toolName, args, principal.actorId(), traceId)
                    : createAction(runId, toolCallKey, toolName, args, principal, started);
            String resultJson = SensitiveDataRedactor.redactJson(json(result));
            Map<String, Object> safeResult = readResult(resultJson);
            repository.completeToolCall(call.id(), resultJson, Duration.between(started,
                    Instant.now(clock)).toMillis(), Instant.now(clock));
            repository.audit(principal.actorId(), "AGENT_TOOL_CALL", "AGENT_TOOL", toolName,
                    "ALLOWED", "runId=" + runId + ";toolCallKey=" + toolCallKey
                            + ";argsHash=" + argsHash, traceId, Instant.now(clock));
            if (metrics != null) metrics.agentTool(toolName, "success", java.time.Duration.between(started, Instant.now(clock)));
            return safeResult;
        } catch (RuntimeException exception) {
            String errorCode = exception.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
            repository.failToolCall(call.id(), errorCode, Duration.between(started,
                    Instant.now(clock)).toMillis(), Instant.now(clock));
            repository.audit(principal.actorId(), "AGENT_TOOL_CALL", "AGENT_TOOL", toolName,
                    "FAILED", "runId=" + runId + ";toolCallKey=" + toolCallKey
                            + ";argsHash=" + argsHash + ";error=" + errorCode, traceId, Instant.now(clock));
            if (metrics != null) metrics.agentTool(toolName, "error", java.time.Duration.between(started, Instant.now(clock)));
            throw exception;
        }
    }

    public String orderRegion(UUID orderId) {
        return businessTools.orderRegion(orderId);
    }

    public Set<String> missingRequiredArguments(String toolName, Map<String, Object> supplied) {
        Set<String> required = switch (toolName) {
            case "get_region_metrics", "compare_peak_plans" -> Set.of("regionId");
            case "get_order_timeline", "diagnose_order", "explain_dispatch" -> Set.of("orderId");
            case "simulate_dispatch" -> hasValue(supplied.get("orderId")) || hasValue(supplied.get("orderIds"))
                    ? Set.of() : Set.of("orderIds");
            case "search_sop" -> Set.of("query", "regionId");
            case "request_cancel", "request_reassignment" -> Set.of("orderId", "expectedVersion", "reasonCode");
            case "request_reschedule" -> Set.of("orderId", "expectedVersion", "reasonCode", "prepEta", "deadline");
            default -> Set.of();
        };
        java.util.HashSet<String> missing = new java.util.HashSet<>(required);
        for (String field : required) {
            Object value = supplied.get(field);
            if (value != null && (!(value instanceof String text) || !text.isBlank())) missing.remove(field);
        }
        if ("compare_peak_plans".equals(toolName)
                && hasValue(supplied.get("from")) != hasValue(supplied.get("to"))) {
            missing.add(hasValue(supplied.get("from")) ? "to" : "from");
        }
        return Set.copyOf(missing);
    }

    private static boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    public Map<String, Object> validatePlan(UUID runId, AgentIntent intent, String toolName,
                                            Map<String, Object> supplied, AgentPrincipal principal,
                                            String traceId) {
        Instant now = Instant.now(clock);
        try {
            return validate(intent, toolName, supplied, principal);
        } catch (RuntimeException exception) {
            repository.audit(principal.actorId(), "AGENT_TOOL_CALL", "AGENT_TOOL", safeToolName(toolName),
                    "DENIED", "runId=" + runId + ";reason=" + exception.getClass().getSimpleName(),
                    traceId, now);
            throw exception;
        }
    }

    private Map<String, Object> validate(AgentIntent intent, String toolName, Map<String, Object> supplied,
                                         AgentPrincipal principal) {
        if (toolName == null || toolName.isBlank()) throw new AgentConflictException("tool name is required");
        String expected = expectedTool(intent);
        if (!toolName.equals(expected)) throw new AgentAccessDeniedException("tool is not allowed for this intent");
        if (supplied == null) throw new IllegalArgumentException("tool arguments are required");
        if (READ_TOOLS.contains(toolName) && !principal.operationsReader()) {
            throw new AgentAccessDeniedException("operations read role is required");
        }
        if (toolName.startsWith("request_") && !principal.operationsApprover()) {
            throw new AgentAccessDeniedException("operations approver role is required");
        }

        Set<String> allowed = switch (toolName) {
            case "get_region_metrics" -> Set.of("regionId", "from", "to", "granularity");
            case "compare_peak_plans" -> Set.of("regionId", "from", "to", "seed");
            case "get_order_timeline" -> Set.of("orderId", "limit");
            case "diagnose_order", "explain_dispatch" -> Set.of("orderId");
            case "simulate_dispatch" -> Set.of("orderId", "orderIds", "regionId", "courierId");
            case "search_sop" -> Set.of("query", "regionId", "category");
            case "request_cancel" -> Set.of("orderId", "expectedVersion", "reasonCode");
            case "request_reschedule" -> Set.of("orderId", "expectedVersion", "reasonCode", "prepEta", "deadline");
            case "request_reassignment" -> Set.of("orderId", "expectedVersion", "reasonCode");
            default -> throw new AgentAccessDeniedException("tool is not registered");
        };
        if (!allowed.containsAll(supplied.keySet())) throw new IllegalArgumentException("tool schema contains unsupported fields");
        Map<String, Object> result = new LinkedHashMap<>();
        switch (toolName) {
            case "get_region_metrics" -> {
                String region = requiredString(supplied, "regionId", 64);
                requireRegion(principal, region);
                result.put("regionId", region);
                Instant from = optionalInstant(supplied, "from");
                Instant to = optionalInstant(supplied, "to");
                if (from != null && to != null && !from.isBefore(to)) {
                    throw new IllegalArgumentException("from must be before to");
                }
                if (from != null) result.put("from", from);
                if (to != null) result.put("to", to);
                String granularity = optionalString(supplied, "granularity", "hour", 16);
                if (!Set.of("hour", "day").contains(granularity)) throw new IllegalArgumentException("granularity is invalid");
                result.put("granularity", granularity);
            }
            case "compare_peak_plans" -> {
                String region = requiredString(supplied, "regionId", 64);
                requireRegion(principal, region);
                result.put("regionId", region);
                Instant from = optionalInstant(supplied, "from");
                Instant to = optionalInstant(supplied, "to");
                if ((from == null) != (to == null) || from != null && !from.isBefore(to)) {
                    throw new IllegalArgumentException("from and to must form a valid time window");
                }
                if (from != null) {
                    result.put("from", from);
                    result.put("to", to);
                }
                result.put("seed", optionalLong(supplied, "seed", 0L));
            }
            case "get_order_timeline", "diagnose_order", "explain_dispatch" -> {
                UUID orderId = requiredUuid(supplied, "orderId");
                requireOrderRegion(principal, orderId);
                result.put("orderId", orderId.toString());
                if ("get_order_timeline".equals(toolName)) {
                    int limit = optionalInt(supplied, "limit", 20);
                    if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
                    result.put("limit", limit);
                }
            }
            case "simulate_dispatch" -> {
                if (supplied.containsKey("orderId") && supplied.containsKey("orderIds")) {
                    throw new IllegalArgumentException("provide orderId or orderIds, not both");
                }
                List<UUID> orderIds = supplied.containsKey("orderIds")
                        ? requiredUuidList(supplied.get("orderIds"), "orderIds")
                        : List.of(requiredUuid(supplied, "orderId"));
                if (orderIds.isEmpty() || orderIds.size() > 3 || new HashSet<>(orderIds).size() != orderIds.size()) {
                    throw new IllegalArgumentException("one to three distinct orderIds are required");
                }
                String region = supplied.containsKey("regionId")
                        ? requiredString(supplied, "regionId", 64) : null;
                String resolvedRegion = region;
                for (UUID orderId : orderIds) {
                    String orderRegion = businessTools.orderRegion(orderId);
                    if (orderRegion == null) throw new AgentNotFoundException("order was not found");
                    requireRegion(principal, orderRegion);
                    if (resolvedRegion == null) resolvedRegion = orderRegion;
                    if (!resolvedRegion.equals(orderRegion)) {
                        throw new IllegalArgumentException("all simulated orders must share one region");
                    }
                }
                if (region != null) requireRegion(principal, region);
                result.put("regionId", resolvedRegion);
                result.put("orderIds", orderIds);
                if (supplied.containsKey("courierId")) {
                    UUID courierId = requiredUuid(supplied, "courierId");
                    String courierRegion = businessTools.courierRegion(courierId);
                    if (courierRegion == null) throw new AgentNotFoundException("courier was not found");
                    requireRegion(principal, courierRegion);
                    if (!resolvedRegion.equals(courierRegion)) {
                        throw new IllegalArgumentException("courier and orders must share one region");
                    }
                    result.put("courierId", courierId);
                }
            }
            case "search_sop" -> {
                String query = requiredString(supplied, "query", 512);
                String region = requiredString(supplied, "regionId", 64);
                requireRegion(principal, region);
                result.put("query", query);
                result.put("regionId", region);
                String category = optionalString(supplied, "category", "SOP", 64);
                if (!category.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("category is invalid");
                result.put("category", category);
            }
            case "request_cancel", "request_reschedule", "request_reassignment" -> {
                UUID orderId = requiredUuid(supplied, "orderId");
                requireOrderRegion(principal, orderId);
                long version = requiredLong(supplied, "expectedVersion");
                String reasonCode = requiredString(supplied, "reasonCode", 64);
                if (version < 1 || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                    throw new IllegalArgumentException("expectedVersion or reasonCode is invalid");
                }
                result.put("orderId", orderId.toString());
                result.put("expectedVersion", version);
                result.put("reasonCode", reasonCode);
                if ("request_reschedule".equals(toolName)) {
                    Instant prepEta = requiredInstant(supplied, "prepEta");
                    Instant deadline = requiredInstant(supplied, "deadline");
                    if (!prepEta.isBefore(deadline)) throw new IllegalArgumentException("prepEta must be before deadline");
                    result.put("prepEta", prepEta.toString());
                    result.put("deadline", deadline.toString());
                }
            }
            default -> throw new AgentAccessDeniedException("tool is not registered");
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> createAction(UUID runId, String toolCallKey, String toolName,
                                              Map<String, Object> args, AgentPrincipal principal, Instant now) {
        UUID targetId = UUID.fromString((String) args.get("orderId"));
        long expectedVersion = ((Number) args.get("expectedVersion")).longValue();
        String actionType = switch (toolName) {
            case "request_cancel" -> "CANCEL_ORDER";
            case "request_reschedule" -> "RESCHEDULE";
            case "request_reassignment" -> "REASSIGN_ORDER";
            default -> throw new AgentAccessDeniedException("tool is not registered");
        };
        Map<String, Object> params = new LinkedHashMap<>(args);
        params.remove("orderId");
        params.remove("expectedVersion");
        String paramsJson = json(params);
        String summary = switch (actionType) {
            case "CANCEL_ORDER" -> "申请取消订单 " + targetId + "，目标版本 " + expectedVersion;
            case "REASSIGN_ORDER" -> "申请重新派单订单 " + targetId + "，目标版本 " + expectedVersion;
            default -> "申请调整订单 " + targetId + " 的出餐和送达时间，目标版本 " + expectedVersion;
        };
        summary += "; reason code " + params.get("reasonCode");
        if ("RESCHEDULE".equals(actionType)) {
            summary += "; prep ETA " + params.get("prepEta") + "; deadline " + params.get("deadline");
        }
        Instant expiresAt = now.plus(ACTION_TTL);
        String idempotencyKey = runId + ":" + toolCallKey;
        AgentActionRecord action = repository.createAction(runId, actionType, targetId, expectedVersion,
                paramsJson, summary, sha256(paramsJson), sha256(actionType + "|" + targetId + "|"
                        + expectedVersion + "|" + sha256(paramsJson) + "|" + summary), principal.actorId(), expiresAt,
                idempotencyKey, now);
        return Map.of("actionId", action.actionId().toString(), "actionType", action.actionType(),
                "targetId", targetId.toString(), "expectedVersion", expectedVersion,
                "summary", action.summaryRedacted(), "summaryHash", action.summaryHash(),
                "expiresAt", action.expiresAt().toString(), "status", action.status().name());
    }

    private void requireOrderRegion(AgentPrincipal principal, UUID orderId) {
        String region = businessTools.orderRegion(orderId);
        if (region == null) throw new AgentNotFoundException("order was not found");
        requireRegion(principal, region);
    }

    private void requireRegion(AgentPrincipal principal, String regionId) {
        if (!principal.canAccessRegion(regionId)) throw new AgentAccessDeniedException("region scope denied");
    }

    private static String expectedTool(AgentIntent intent) {
        return switch (intent) {
            case REGION_METRICS -> "get_region_metrics";
            case PEAK_PLAN_COMPARISON -> "compare_peak_plans";
            case ORDER_LOOKUP -> "get_order_timeline";
            case ORDER_DIAGNOSIS -> "diagnose_order";
            case DISPATCH_EXPLANATION -> "explain_dispatch";
            case DISPATCH_SIMULATION -> "simulate_dispatch";
            case SOP_QA -> "search_sop";
            case CANCEL_ORDER -> "request_cancel";
            case RESCHEDULE -> "request_reschedule";
            case REASSIGN_ORDER -> "request_reassignment";
            case UNKNOWN -> throw new AgentAccessDeniedException("unknown intent cannot invoke tools");
        };
    }

    private static String requiredString(Map<String, Object> values, String key, int max) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) {
            throw new IllegalArgumentException(key + " is required and must be at most " + max + " characters");
        }
        return text.trim();
    }

    private static String optionalString(Map<String, Object> values, String key, String fallback, int max) {
        if (!values.containsKey(key) || values.get(key) == null) return fallback;
        return requiredString(values, key, max);
    }

    private static List<UUID> requiredUuidList(Object value, String key) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException(key + " must be an array");
        List<UUID> ids = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text)) throw new IllegalArgumentException(key + " must contain UUID strings");
            try {
                ids.add(UUID.fromString(text));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(key + " must contain valid UUIDs");
            }
        }
        return List.copyOf(ids);
    }

    private static UUID requiredUuid(Map<String, Object> values, String key) {
        try {
            return UUID.fromString(requiredString(values, key, 36));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " must be a UUID");
        }
    }

    private static long requiredLong(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " is required");
        long converted = number.longValue();
        if ((number instanceof Double || number instanceof Float)
                && (!Double.isFinite(number.doubleValue()) || number.doubleValue() != converted)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (number instanceof java.math.BigDecimal decimal && decimal.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (number instanceof java.math.BigInteger integer && integer.bitLength() > 63) {
            throw new IllegalArgumentException(key + " is outside the supported range");
        }
        return converted;
    }

    private static int optionalInt(Map<String, Object> values, String key, int fallback) {
        if (!values.containsKey(key) || values.get(key) == null) return fallback;
        Object value = values.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        int converted = number.intValue();
        if (converted != number.doubleValue()) throw new IllegalArgumentException(key + " must be an integer");
        return converted;
    }

    private static long optionalLong(Map<String, Object> values, String key, long fallback) {
        if (!values.containsKey(key) || values.get(key) == null) return fallback;
        return requiredLong(values, key);
    }

    private static Instant optionalInstant(Map<String, Object> values, String key) {
        if (!values.containsKey(key) || values.get(key) == null) return null;
        return requiredInstant(values, key);
    }

    private static Instant requiredInstant(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Instant instant) return instant;
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (RuntimeException ignored) {
                throw new IllegalArgumentException(key + " must be an ISO-8601 instant with timezone");
            }
        }
        throw new IllegalArgumentException(key + " is required and must include a timezone");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("could not serialize Agent data", exception);
        }
    }

    private Map<String, Object> readResult(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("stored Agent tool result is invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String safeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) return "unknown";
        return toolName.replaceAll("[^A-Za-z0-9_.-]", "_").substring(0, Math.min(128, toolName.length()));
    }
}
