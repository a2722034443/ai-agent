package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IntentParserAgent {
    private static final String SYSTEM_PROMPT = """
            你是本地生活规划助手。从用户输入中提取意图，只输出 JSON，不要输出解释。
            输出格式：
            {
              "scenario": "family|friends|couple|solo|unknown",
              "group": {"total": 人数, "composition": "中文描述", "childAge": 年龄或 null},
              "time_window": {"start": "HH:MM", "end": "HH:MM", "minHours": 4, "maxHours": 6},
              "location": {"city": "城市", "district": null, "radius": "nearby|city"},
              "hard_constraints": ["中文或稳定英文约束"],
              "soft_preferences": {"budget": "low|medium|high", "vibe": "中文描述"}
            }
            规则：孩子、老人、安全、过敏、必须去属于硬约束；减肥、少折腾、预算属于偏好。
            没有城市时默认大连；没有时间时默认今天下午 14:00-20:00。
            """;

    private final MimoClient mimoClient;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;

    public IntentParserAgent(MimoClient mimoClient, ToolTraceService traceService, ObjectMapper objectMapper) {
        this.mimoClient = mimoClient;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> parse(UUID planId, String message) {
        long start = System.currentTimeMillis();
        try {
            String content = mimoClient.complete(SYSTEM_PROMPT, message == null ? "" : message);
            Map<String, Object> intent = normalize(objectMapper.readValue(extractJsonObject(content), new TypeReference<>() {}));
            traceService.trace(planId, "IntentParserAgent", "ok", start,
                    Map.of("message", safeSnippet(message)),
                    Map.of("provider", "mimo", "mode", "real", "scenario", intent.get("scenario")));
            return intent;
        } catch (Exception e) {
            Map<String, Object> fallback = keywordFallback(message);
            traceService.trace(planId, "IntentParserAgent", "fallback", start,
                    Map.of("message", safeSnippet(message)),
                    traceOutput("fallback", fallback.get("scenario"), e));
            return fallback;
        }
    }

    private Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> intent = new LinkedHashMap<>(raw);
        intent.putIfAbsent("scenario", "unknown");
        intent.putIfAbsent("group", defaultGroup());
        intent.putIfAbsent("location", defaultLocation("city"));
        intent.putIfAbsent("hard_constraints", List.of());
        intent.putIfAbsent("soft_preferences", Map.of("budget", "medium", "vibe", "轻松"));
        Map<String, Object> time = new LinkedHashMap<>(castMap(intent.get("time_window")));
        time.putIfAbsent("start", "14:00");
        time.putIfAbsent("end", "20:00");
        time.putIfAbsent("minHours", 4);
        time.putIfAbsent("maxHours", 6);
        intent.put("time_window", time);
        return intent;
    }

    private Map<String, Object> traceOutput(String mode, Object scenario, Exception e) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", "mimo");
        output.put("mode", mode);
        output.put("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        output.put("scenario", scenario == null ? "unknown" : scenario);
        return output;
    }

    private Map<String, Object> defaultGroup() {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("total", 2);
        group.put("composition", "同行人");
        group.put("childAge", null);
        return group;
    }

    private Map<String, Object> defaultLocation(String radius) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("city", "大连");
        location.put("district", null);
        location.put("radius", radius);
        return location;
    }

    Map<String, Object> keywordFallback(String message) {
        String text = message == null ? "" : message;
        boolean family = containsAny(text, "孩子", "老婆", "家庭", "亲子");
        boolean friends = containsAny(text, "朋友", "4个人", "四个人", "聚会");
        boolean lowCal = containsAny(text, "减肥", "低卡", "清淡", "轻食");
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("total", family ? 3 : friends ? 4 : 2);
        group.put("composition", family ? "2大1小" : friends ? "朋友同行" : "同行人");
        group.put("childAge", family ? 5 : null);
        List<String> hard = new ArrayList<>();
        if (family) hard.add("\u513f\u7ae5\u53cb\u597d");
        if (text.contains("\u522b\u79bb\u5bb6\u592a\u8fdc") || text.contains("\u4e0d\u8981\u592a\u8fdc")) {
            hard.add("\u8ddd\u79bb\u8fd1");
        }
        if (lowCal) hard.add("\u4f4e\u5361\u4f18\u5148");
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("scenario", family ? "family" : friends ? "friends" : "unknown");
        intent.put("group", group);
        intent.put("time_window", Map.of("start", "14:00", "end", "20:00", "minHours", 4, "maxHours", 6));
        intent.put("location", defaultLocation(hard.contains("\u8ddd\u79bb\u8fd1") ? "nearby" : "city"));
        intent.put("hard_constraints", hard);
        intent.put("soft_preferences", Map.of("budget", "medium", "vibe", family ? "\u4eb2\u5b50\u8f7b\u677e" : "\u8f7b\u677e\u793e\u4ea4"));
        return intent;
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("MiMo 输出不是 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String safeSnippet(String message) {
        if (message == null) return "";
        return message.length() <= 80 ? message : message.substring(0, 80);
    }
}
