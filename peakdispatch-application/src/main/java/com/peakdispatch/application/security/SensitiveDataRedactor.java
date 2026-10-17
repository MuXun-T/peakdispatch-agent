package com.peakdispatch.application.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveDataRedactor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)([\"']?authorization[\"']?\\s*[:=]\\s*[\"']?)(?:(?:bearer|basic)\\s+)?[^\\s,;\"'}]+");
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)([\"']?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|offerToken|api[_-]?key|password|passwd|secret|credential|token)[\"']?\\s*[:=]\\s*[\"']?)(?:bearer\\s+)?[^\\s,;\"'}]+");
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern LABELED_ADDRESS = Pattern.compile("(?i)([\"']?(?:address|地址)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\\r\\n\"'}]+|(?:[\\p{IsHan}]{2,}(?:省|市|区|县|镇|乡|街道|路|街|巷)[\\p{IsHan}A-Za-z0-9#-]{0,24}\\d{1,5}(?:号|弄|幢|栋|楼|室))");
    private static final Pattern LABELED_NAME = Pattern.compile("(?i)([\"']?(?:name|姓名)[\"']?\\s*[:=]\\s*[\"']?)[\\p{IsHan}]{2,4}|(name\\s*[:=]\\s*)[A-Za-z][A-Za-z .'-]{1,60}");
    private static final Set<String> SENSITIVE_JSON_FIELDS = Set.of("authorization", "token", "offertoken",
            "accesstoken", "refreshtoken", "idtoken", "apikey", "password", "passwd", "secret",
            "credential", "credentials", "phone", "mobile", "email", "address", "name");

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) return value;
        String redacted = AUTHORIZATION.matcher(value).replaceAll("$1[AUTHORIZATION]");
        redacted = CREDENTIAL.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = JWT.matcher(redacted).replaceAll("[TOKEN]");
        redacted = EMAIL.matcher(redacted).replaceAll("[EMAIL]");
        redacted = PHONE.matcher(redacted).replaceAll("[PHONE]");
        redacted = LABELED_ADDRESS.matcher(redacted).replaceAll("$1[ADDRESS]");
        redacted = LABELED_NAME.matcher(redacted).replaceAll("$1[NAME]");
        return redacted;
    }

    public static String redactJson(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            JsonNode root = JSON.readTree(value);
            redactTree(root);
            return JSON.writeValueAsString(root);
        } catch (Exception invalidJson) {
            return redact(value);
        }
    }

    private static void redactTree(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.fieldNames().forEachRemaining(field -> {
                JsonNode child = object.get(field);
                String normalized = field.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
                if (SENSITIVE_JSON_FIELDS.contains(normalized)) {
                    object.put(field, "[REDACTED]");
                } else if (child != null && child.isTextual()) {
                    object.put(field, redact(child.asText()));
                } else if (child != null) {
                    redactTree(child);
                }
            });
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isTextual()) array.set(index, JSON.getNodeFactory().textNode(redact(child.asText())));
                else redactTree(child);
            }
        }
    }
}
