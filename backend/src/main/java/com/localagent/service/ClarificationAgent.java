package com.localagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClarificationAgent {
    private static final List<String> ALLOWED_KEYS = List.of(
            "location", "timeWindow", "duration", "group", "budget", "preferences"
    );

    private final ToolTraceService traceService;

    public ClarificationAgent(ToolTraceService traceService) {
        this.traceService = traceService;
    }

    public Map<String, Object> clarify(UUID planId, String message, Map<String, Object> intent,
                                       Map<String, Object> fallback) {
        long start = System.currentTimeMillis();
        if (fallback == null || fallback.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = normalize(fallback);
        traceService.trace(planId, "ClarificationAgent", "ok", start,
                Map.of("message", safeSnippet(message), "fallbackFieldCount", castList(fallback.get("fields")).size()),
                Map.of("provider", "local", "mode", "rule", "fieldCount", castList(result.get("fields")).size()));
        return result;
    }

    private Map<String, Object> normalize(Map<String, Object> fallback) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", string(fallback.getOrDefault("message", "还需要补齐几个关键信息，补齐后我再查询真实地点并生成方案。")));

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> field : castList(fallback.get("fields"))) {
            String key = string(field.get("key"));
            if (!ALLOWED_KEYS.contains(key)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>(field);
            List<String> suggestions = suggestions(field.get("suggestions"));
            normalized.put("type", "text");
            normalized.put("suggestions", suggestions);
            normalized.put("options", options(field.get("options"), suggestions));
            normalized.put("allowCustom", true);
            normalized.putIfAbsent("reason", "补齐后才能生成真实可执行方案。");
            normalized.putIfAbsent("expectedAnswerHint", "用自然语言填写即可。");
            fields.add(normalized);
        }
        result.put("fields", fields);
        result.put("missingFields", fields.stream().map(field -> field.get("key")).toList());
        return result;
    }

    private List<String> suggestions(Object value) {
        List<String> suggestions = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = string(item);
                if (!text.isBlank() && !suggestions.contains(text)) {
                    suggestions.add(text);
                }
            }
        }
        return suggestions.stream().limit(3).toList();
    }

    private List<Map<String, Object>> options(Object value, List<String> suggestions) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    String text = string(raw.get("text"));
                    if (!text.isBlank()) {
                        String code = string(raw.get("code"));
                        options.add(Map.of("code", code.isBlank() ? String.valueOf((char) ('A' + options.size())) : code, "text", text));
                    }
                }
            }
        }
        if (options.isEmpty()) {
            for (int i = 0; i < suggestions.size(); i++) {
                options.add(Map.of("code", String.valueOf((char) ('A' + i)), "text", suggestions.get(i)));
            }
        }
        return options;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeSnippet(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 80 ? message : message.substring(0, 80);
    }
}
