package com.peakdispatch.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders only facts that are present in a validated, redacted tool result. */
public final class AgentAnswerRenderer {
    private static final String INSUFFICIENT_EVIDENCE = "工具结果缺少可验证证据，无法生成该结论，请重新查询。";

    private AgentAnswerRenderer() {
    }

    public static String render(AgentIntent intent, Map<String, Object> result, ObjectMapper mapper) {
        if (intent == null || result == null || mapper == null) return INSUFFICIENT_EVIDENCE;
        try {
            return switch (intent) {
                case REGION_METRICS -> regionMetrics(result);
                case ORDER_LOOKUP -> orderLookup(result);
                case ORDER_DIAGNOSIS -> diagnosis(result);
                case DISPATCH_EXPLANATION -> dispatchExplanation(result);
                case DISPATCH_SIMULATION -> dispatchSimulation(result, mapper);
                case PEAK_PLAN_COMPARISON -> peakPlans(result, mapper);
                case SOP_QA -> sop(result);
                case CANCEL_ORDER, RESCHEDULE, REASSIGN_ORDER -> action(result);
                default -> INSUFFICIENT_EVIDENCE;
            };
        } catch (RuntimeException ignored) {
            return INSUFFICIENT_EVIDENCE;
        }
    }

    /** Returns a safe citation map only when all source identity fields are present. */
    public static Optional<Map<String, Object>> validatedCitation(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Optional.empty();
        String citationId = nonBlank(raw.get("citationId"));
        String title = nonBlank(raw.get("title"));
        String version = nonBlank(raw.get("version"));
        String uri = nonBlank(raw.get("uri"));
        if (citationId == null || title == null || version == null || uri == null
                || !uri.matches("(?i)(https?|knowledge)://[^\\s]+")) return Optional.empty();
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("citationId", citationId);
        safe.put("title", title);
        safe.put("version", version);
        safe.put("uri", uri);
        for (String key : List.of("section", "excerpt", "score")) {
            Object optional = raw.get(key);
            if (optional != null) safe.put(key, optional);
        }
        return Optional.of(Map.copyOf(safe));
    }

    private static String regionMetrics(Map<String, Object> result) {
        String region = requiredString(result, "regionId");
        String from = requiredString(result, "from");
        String to = requiredString(result, "to");
        String asOf = requiredString(result, "asOf");
        String definition = requiredString(result, "definition");
        Map<String, Object> metrics = requiredMap(result, "metrics");
        Object waiting = required(metrics, "waitingDispatch");
        Object online = required(metrics, "onlineCouriers");
        Object failures = required(metrics, "dispatchFailures");
        Object escalations = required(metrics, "escalations");
        return "区域 " + region + " 在 " + from + " 至 " + to + " 的统计：待派 " + waiting
                + "，在线骑手 " + online + "，派单失败 " + failures + "，升级 " + escalations
                + "。数据时间 " + asOf + "；口径：" + definition + "。";
    }

    private static String orderLookup(Map<String, Object> result) {
        return "订单 " + requiredString(result, "orderId") + " 当前状态 " + requiredString(result, "status")
                + "，版本 " + required(result, "version") + "，时间线事件数 "
                + requiredList(result, "events").size() + "。数据时间 " + requiredString(result, "asOf") + "。";
    }

    private static String diagnosis(Map<String, Object> result) {
        List<?> reasons = requiredList(result, "reasonCodes");
        List<?> evidence = requiredList(result, "evidence");
        Map<String, Object> bindings = requiredMap(result, "reasonEvidence");
        Set<String> evidenceIds = new HashSet<>();
        for (Object value : evidence) {
            if (value instanceof Map<?, ?> map && nonBlank(map.get("evidenceId")) != null) {
                evidenceIds.add(nonBlank(map.get("evidenceId")));
            }
        }
        for (Object reasonValue : reasons) {
            String reason = nonBlank(reasonValue);
            if (reason == null) throw new IllegalArgumentException("reason code is invalid");
            Object boundValue = bindings.get(reason);
            if (!(boundValue instanceof List<?> bound) || bound.isEmpty()
                    || bound.stream().map(AgentAnswerRenderer::nonBlank).anyMatch(id -> id == null || !evidenceIds.contains(id))) {
                throw new IllegalArgumentException("diagnosis reason is not bound to evidence");
            }
        }
        String timing = "";
        String prepEta = optionalString(result, "prepEta");
        String deadline = optionalString(result, "deadline");
        if (prepEta != null && deadline != null) {
            timing = "；商家 ETA " + prepEta + "，订单截止 " + deadline;
        }
        List<String> evidenceRefs = bindings.values().stream().filter(List.class::isInstance)
                .map(List.class::cast).flatMap(List::stream).map(AgentAnswerRenderer::nonBlank)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return "订单 " + requiredString(result, "orderId") + " 当前状态 " + requiredString(result, "status")
                + "。可确认原因代码：" + reasons + "；原因证据绑定数 " + bindings.size()
                + "（" + evidenceRefs + "）；证据条数 " + evidence.size() + timing
                + "；未能从时间线确认的内容："
                + requiredList(result, "unknowns") + "。";
    }

    private static String dispatchExplanation(Map<String, Object> result) {
        String source = optionalString(result, "source");
        String suffix = source == null ? "" : " 来源：" + source + "。";
        return requiredString(result, "explanation") + " 派单证据条数："
                + requiredList(result, "dispatchEvidence").size() + "。" + suffix;
    }

    private static String dispatchSimulation(Map<String, Object> result, ObjectMapper mapper) {
        Object mutates = required(result, "mutatesDispatch");
        if (!(mutates instanceof Boolean value) || value) throw new IllegalArgumentException("simulation is not read-only");
        String region = requiredString(result, "regionId");
        List<?> orderIds = requiredList(result, "orderIds");
        List<?> candidates = requiredList(result, "candidates");
        List<?> constraints = requiredList(result, "constraints");
        required(result, "inputVersions");
        long count = result.get("candidateCount") instanceof Number number ? number.longValue() : candidates.size();
        long executable = candidates.stream().filter(candidateValue -> candidateValue instanceof Map<?, ?> map
                && Boolean.TRUE.equals(map.get("executable"))).count();
        List<Map<String, Object>> details = candidates.stream().filter(Map.class::isInstance)
                .map(Map.class::cast).map(candidate -> {
                    Map<String, Object> safe = new LinkedHashMap<>();
                    for (String field : List.of("courierId", "executable", "failureReasons", "routeOrder",
                            "estimatedTimes", "estimatedDistanceKm", "estimatedElapsedSeconds", "capacity")) {
                        if (candidate.containsKey(field)) safe.put(field, candidate.get(field));
                    }
                    return safe;
                }).toList();
        try {
            String renderedCandidates = mapper.writeValueAsString(details);
            return "Read-only route simulation for region " + region + " and orders " + orderIds + " evaluated "
                    + count + " couriers; " + executable + " displayed route(s) satisfy all hard constraints. "
                    + "Constraints: " + constraints + ". Candidate ETAs and route details: " + renderedCandidates
                    + (Boolean.TRUE.equals(result.get("candidatePoolTruncated"))
                    ? " The courier search was capped at 50 candidates." : "");
        } catch (Exception exception) {
            return INSUFFICIENT_EVIDENCE;
        }
    }

    private static String peakPlans(Map<String, Object> result, ObjectMapper mapper) {
        for (String key : List.of("rulesVersion", "regionId", "from", "to", "asOf", "source",
                "inputMetrics", "constraints", "plans", "limitations")) required(result, key);
        try {
            return "确定性区域方案比较结果：" + mapper.writeValueAsString(result);
        } catch (Exception exception) {
            return INSUFFICIENT_EVIDENCE;
        }
    }

    private static String sop(Map<String, Object> result) {
        String status = requiredString(result, "status");
        if ("NO_EVIDENCE".equals(status)) {
            return "没有找到适用于该区域的有效 SOP 证据，不能据此给出规范结论。";
        }
        if (!"EVIDENCE".equals(status)) return INSUFFICIENT_EVIDENCE;
        Object raw = result.get("citations");
        if (!(raw instanceof List<?> values) || values.isEmpty()) return INSUFFICIENT_EVIDENCE;
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Object value : values) {
            Optional<Map<String, Object>> citation = validatedCitation(value);
            if (citation.isEmpty()) return INSUFFICIENT_EVIDENCE;
            citations.add(citation.get());
        }
        String sources = citations.stream().map(citation -> citation.get("title") + "（版本 "
                + citation.get("version") + "，来源 " + citation.get("uri") + "，引用 "
                + citation.get("citationId") + "）").collect(Collectors.joining("；"));
        List<String> passages = citations.stream().map(citation -> {
            String excerpt = optionalString(citation, "excerpt");
            if (excerpt == null) return null;
            String section = optionalString(citation, "section");
            String heading = citation.get("title") + (section == null ? "" : " / " + section);
            return heading + ": " + excerpt.substring(0, Math.min(1000, excerpt.length()))
                    + " [" + citation.get("citationId") + "]";
        }).filter(java.util.Objects::nonNull).toList();
        if (passages.isEmpty()) {
            return "找到 " + citations.size() + " 条区域 SOP 引用，但检索结果没有可回答的正文片段。来源：" + sources;
        }
        return "根据检索到的 SOP 内容：" + String.join("\n", passages) + "\n来源：" + sources;
    }

    private static String action(Map<String, Object> result) {
        return "操作申请已创建，actionId=" + requiredString(result, "actionId") + "，状态 "
                + requiredString(result, "status") + "，等待另一位运营审批人处理。";
    }

    private static Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        Object value = required(source, key);
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("field is not an object");
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((entry, item) -> result.put(String.valueOf(entry), item));
        return result;
    }

    private static List<?> requiredList(Map<String, Object> source, String key) {
        Object value = required(source, key);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("field is not an array");
        return list;
    }

    private static String requiredString(Map<String, Object> source, String key) {
        String value = nonBlank(required(source, key));
        if (value == null) throw new IllegalArgumentException("field is required");
        return value;
    }

    private static String optionalString(Map<String, Object> source, String key) {
        return source.containsKey(key) ? nonBlank(source.get(key)) : null;
    }

    private static Object required(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) throw new IllegalArgumentException("field is required");
        return value;
    }

    private static String nonBlank(Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return null;
    }
}
