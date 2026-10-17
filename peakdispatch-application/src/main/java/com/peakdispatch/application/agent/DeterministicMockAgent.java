package com.peakdispatch.application.agent;

import java.util.Locale;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DeterministicMockAgent implements AgentIntentClassifier {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    public AgentIntent recognize(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (contains(normalized, "取消", "cancel")) return AgentIntent.CANCEL_ORDER;
        if (contains(normalized, "更换骑手", "换骑手", "改派骑手", "改派", "重新派", "重派", "reassign")) {
            return AgentIntent.REASSIGN_ORDER;
        }
        if (contains(normalized, "reschedule", "调整时间", "修改出餐", "修改送达")) return AgentIntent.RESCHEDULE;
        if (contains(normalized, "指标", "區域指標", "區域風險", "待派量", "在線騎手",
                "dashboard", "metrics")) {
            return AgentIntent.REGION_METRICS;
        }
        if (contains(normalized, "高峰方案", "方案对比", "方案比较", "调度摘要", "高峰调度",
                "compare peak", "peak plans")) return AgentIntent.PEAK_PLAN_COMPARISON;
        if (contains(normalized, "诊断", "为什么", "为何", "异常", "原因", "diagnos")) {
            return AgentIntent.ORDER_DIAGNOSIS;
        }
        if (contains(normalized, "模拟派单", "派单模拟", "simulate dispatch")) {
            return AgentIntent.DISPATCH_SIMULATION;
        }
        if (contains(normalized, "派单解释", "派单情况", "派单记录", "dispatch", "接单")) {
            return AgentIntent.DISPATCH_EXPLANATION;
        }
        if (contains(normalized, "sop", "规范", "流程", "怎么操作", "如何处理", "policy")) {
            return AgentIntent.SOP_QA;
        }
        if (UUID_PATTERN.matcher(normalized).find()) return AgentIntent.ORDER_LOOKUP;
        return AgentIntent.UNKNOWN;
    }

    @Override
    public AgentIntentClassification classify(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<AgentIntent> matches = new java.util.ArrayList<>();
        if (contains(normalized, "取消", "cancel")) matches.add(AgentIntent.CANCEL_ORDER);
        if (contains(normalized, "更换骑手", "改派骑手", "换骑手", "改派", "重新派", "重派", "reassign")) {
            matches.add(AgentIntent.REASSIGN_ORDER);
        }
        if (contains(normalized, "reschedule", "调整时间", "修改出餐", "修改送达")) matches.add(AgentIntent.RESCHEDULE);
        if (contains(normalized, "指标", "区域风险", "待派量", "在线骑手", "dashboard", "metrics")) {
            matches.add(AgentIntent.REGION_METRICS);
        }
        if (contains(normalized, "高峰方案", "方案对比", "方案比较", "调度摘要", "高峰调度",
                "compare peak", "peak plans")) matches.add(AgentIntent.PEAK_PLAN_COMPARISON);
        if (contains(normalized, "诊断", "为什么", "为何", "异常", "原因", "diagnos")) {
            matches.add(AgentIntent.ORDER_DIAGNOSIS);
        }
        boolean simulation = contains(normalized, "模拟派单", "派单模拟", "simulate dispatch");
        if (simulation) matches.add(AgentIntent.DISPATCH_SIMULATION);
        else if (contains(normalized, "派单解释", "派单情况", "派单记录", "dispatch", "接单")) {
            matches.add(AgentIntent.DISPATCH_EXPLANATION);
        }
        if (contains(normalized, "sop", "规范", "流程", "怎么操作", "如何处理", "policy")) {
            matches.add(AgentIntent.SOP_QA);
        }
        if (matches.size() > 1) {
            return new AgentIntentClassification(AgentIntent.UNKNOWN, 0.35,
                    "AMBIGUOUS_INTENT_CUES", List.of("intent"));
        }
        AgentIntent intent = recognize(message);
        double confidence = switch (intent) {
            case UNKNOWN -> 0.0;
            case ORDER_LOOKUP -> 0.76;
            default -> 0.96;
        };
        List<String> missing = switch (intent) {
            case REGION_METRICS, PEAK_PLAN_COMPARISON, SOP_QA -> List.of("regionId");
            case ORDER_LOOKUP, ORDER_DIAGNOSIS, DISPATCH_EXPLANATION, DISPATCH_SIMULATION -> List.of("orderId");
            case CANCEL_ORDER, RESCHEDULE, REASSIGN_ORDER -> List.of("orderId", "expectedVersion", "reasonCode");
            case UNKNOWN -> List.of("intent");
        };
        return new AgentIntentClassification(intent, confidence,
                intent == AgentIntent.UNKNOWN ? "NO_REGISTERED_INTENT_MATCH" : "DETERMINISTIC_KEYWORD_MATCH",
                missing);
    }

    /**
     * Builds the same constrained plan used by the default mock provider. Values come only from
     * explicit request slots or an order UUID present in the message; missing values ask a question.
     */
    @Override
    public AgentPlan plan(String message, Map<String, Object> suppliedSlots) {
        if (suppliedSlots == null) throw new IllegalArgumentException("supplied slots are required");
        AgentIntentClassification classification = classify(message);
        AgentIntent intent = classification.intent();
        if (intent == AgentIntent.UNKNOWN || classification.confidence() < 0.55) {
            return clarification(intent, classification.confidence(),
                    "我无法可靠识别这条请求，请补充要查询或执行的运营操作。");
        }

        Map<String, Object> args = new LinkedHashMap<>();
        String tool;
        switch (intent) {
            case REGION_METRICS -> {
                String region = text(suppliedSlots.get("regionId"));
                if (region == null) return clarification(intent, classification.confidence(),
                        "请提供有权限访问的 regionId。");
                tool = "get_region_metrics";
                args.put("regionId", region);
                copy(suppliedSlots, args, "from", "to");
                args.put("granularity", text(suppliedSlots.get("granularity")) == null
                        ? "hour" : text(suppliedSlots.get("granularity")));
            }
            case PEAK_PLAN_COMPARISON -> {
                String region = text(suppliedSlots.get("regionId"));
                if (region == null) return clarification(intent, classification.confidence(),
                        "请提供有权限访问的 regionId。");
                if (present(suppliedSlots.get("from")) != present(suppliedSlots.get("to"))) {
                    return clarification(intent, classification.confidence(),
                            "请同时提供完整的 from 和 to 时间范围。");
                }
                tool = "compare_peak_plans";
                args.put("regionId", region);
                copy(suppliedSlots, args, "from", "to");
                args.put("seed", suppliedSlots.getOrDefault("seed", 0L));
            }
            case ORDER_LOOKUP, ORDER_DIAGNOSIS, DISPATCH_EXPLANATION, DISPATCH_SIMULATION -> {
                UUID orderId = orderId(suppliedSlots.get("orderId"));
                if (orderId == null) orderId = orderId(message);
                if (orderId == null) return clarification(intent, classification.confidence(),
                        "请提供订单 UUID。");
                tool = switch (intent) {
                    case ORDER_LOOKUP -> "get_order_timeline";
                    case ORDER_DIAGNOSIS -> "diagnose_order";
                    case DISPATCH_SIMULATION -> "simulate_dispatch";
                    default -> "explain_dispatch";
                };
                if (intent == AgentIntent.DISPATCH_SIMULATION) {
                    List<UUID> orderIds = orderIds(message);
                    if (orderIds.size() > 3) return clarification(intent, classification.confidence(),
                            "一次只能预览 1 到 3 个订单，请缩小订单范围。");
                    if (orderIds.size() > 1) args.put("orderIds", orderIds.stream().map(UUID::toString).toList());
                    else args.put("orderId", orderId.toString());
                } else {
                    args.put("orderId", orderId.toString());
                }
                if ("get_order_timeline".equals(tool)) args.put("limit", 20);
            }
            case SOP_QA -> {
                String region = text(suppliedSlots.get("regionId"));
                if (region == null) return clarification(intent, classification.confidence(),
                        "请提供有权限访问的 regionId。");
                tool = "search_sop";
                args.put("query", message);
                args.put("regionId", region);
                args.put("category", text(suppliedSlots.get("category")) == null
                        ? "SOP" : text(suppliedSlots.get("category")));
            }
            case CANCEL_ORDER, RESCHEDULE, REASSIGN_ORDER -> {
                UUID orderId = orderId(suppliedSlots.get("orderId"));
                if (orderId == null) orderId = orderId(message);
                String reason = text(suppliedSlots.get("reasonCode"));
                if (orderId == null) return clarification(intent, classification.confidence(),
                        "请提供订单 UUID。");
                if (!(suppliedSlots.get("expectedVersion") instanceof Number)) {
                    return clarification(intent, classification.confidence(),
                            "请提供订单当前 expectedVersion，我不会猜测版本。");
                }
                if (reason == null) return clarification(intent, classification.confidence(),
                        "请提供操作原因代码 reasonCode。");
                tool = intent == AgentIntent.CANCEL_ORDER ? "request_cancel"
                        : intent == AgentIntent.RESCHEDULE ? "request_reschedule" : "request_reassignment";
                args.put("orderId", orderId.toString());
                args.put("expectedVersion", suppliedSlots.get("expectedVersion"));
                args.put("reasonCode", reason);
                if (intent == AgentIntent.RESCHEDULE) {
                    if (!present(suppliedSlots.get("prepEta")) || !present(suppliedSlots.get("deadline"))) {
                        return clarification(intent, classification.confidence(),
                                "请同时提供带时区的 prepEta 和 deadline。");
                    }
                    args.put("prepEta", suppliedSlots.get("prepEta"));
                    args.put("deadline", suppliedSlots.get("deadline"));
                }
            }
            default -> throw new IllegalStateException("unsupported intent");
        }
        return new AgentPlan(intent, classification.confidence(), false, null, tool, args);
    }

    @Override
    public String modelId() {
        return "deterministic-mock-v1";
    }

    @Override
    public String promptVersion() {
        return "mock-v1";
    }

    public UUID orderId(String message) {
        Matcher matcher = UUID_PATTERN.matcher(message == null ? "" : message);
        return matcher.find() ? UUID.fromString(matcher.group()) : null;
    }

    private static List<UUID> orderIds(String message) {
        Matcher matcher = UUID_PATTERN.matcher(message == null ? "" : message);
        List<UUID> ids = new java.util.ArrayList<>();
        while (matcher.find() && ids.size() < 4) ids.add(UUID.fromString(matcher.group()));
        return List.copyOf(ids);
    }

    private static AgentPlan clarification(AgentIntent intent, double confidence, String question) {
        return new AgentPlan(intent, confidence, true, question, null, Map.of());
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (present(source.get(key))) target.put(key, source.get(key));
    }

    private static UUID orderId(Object value) {
        if (value instanceof UUID id) return id;
        return value instanceof String text ? parseUuid(text) : null;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static boolean present(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private static boolean contains(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
