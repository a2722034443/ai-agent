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
public class ClarificationAgent {
    private static final String SYSTEM_PROMPT = """
            你是本地生活规划产品的澄清 Agent。
            你的任务不是生成方案，而是判断用户输入和已解析 intent 还缺哪些“生成真实本地生活方案的必要条件”，并生成交互式澄清卡片。

            必要条件：
            1. location：必须有可被地图检索的城市 + 商圈/地标/地址。用户说“我附近”“附近”“当前位置附近”不算完整。
            2. timeWindow：必须有明确开始时间。只有“上午/下午/晚上”不算完整；“早上12点/上午12点”属于冲突表达，需要重问。
            3. duration：必须有游玩时长或结束时间。
            4. group：必须知道同行人数和构成。
            5. budget：必须知道总预算或预算区间。
            6. preferences：必须知道核心需求类型，例如约会、亲子、朋友聚会、展览、晚餐、户外等。

            生成规则：
            - 只输出 JSON 对象，不输出解释。
            - 选项必须根据用户原句动态生成，全国通用，不得默认任何城市；只有用户明确提到城市时才可在选项中使用该城市。
            - 每个字段必须包含 allowCustom=true，表示前端要展示自定义输入。
            - 对 location，如果不知道用户城市，不要给“我所在城市 + ...”这种可点击选项；suggestions 可以为空数组，并用 question 提醒用户手动输入“城市 + 商圈/地标/地址”。
            - 不要编造用户所在城市，不要默认“大连”。
            - 字段数量尽量少，只问真正缺失或无效的信息。

            JSON 格式：
            {
              "message": "一句中文提示",
              "missingFields": ["location"],
              "fields": [
                {
                  "key": "location",
                  "label": "地点",
                  "question": "中文问题",
                  "type": "text|choice|number",
                  "suggestions": ["选项A", "选项B", "选项C"],
                  "allowCustom": true,
                  "reason": "为什么缺这个字段"
                }
              ]
            }
            """;

    private final MimoClient mimoClient;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;

    public ClarificationAgent(MimoClient mimoClient, ToolTraceService traceService, ObjectMapper objectMapper) {
        this.mimoClient = mimoClient;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> clarify(UUID planId, String message, Map<String, Object> intent,
                                       Map<String, Object> fallback) {
        long start = System.currentTimeMillis();
        if (fallback == null || fallback.isEmpty()) {
            return Map.of();
        }
        try {
            String userPrompt = objectMapper.writeValueAsString(Map.of(
                    "userMessage", message == null ? "" : message,
                    "currentIntent", intent == null ? Map.of() : intent,
                    "ruleDetectedMissingFields", fallback.getOrDefault("fields", List.of())
            ));
            String content = mimoClient.complete(SYSTEM_PROMPT, userPrompt);
            Map<String, Object> llmResult = normalize(objectMapper.readValue(extractJsonObject(content), new TypeReference<>() {}));
            if (castList(llmResult.get("fields")).isEmpty()) {
                return Map.of();
            }
            traceService.trace(planId, "ClarificationAgent", "ok", start,
                    Map.of("message", safeSnippet(message), "fallbackFieldCount", castList(fallback.get("fields")).size()),
                    Map.of("provider", "mimo", "mode", "real", "fieldCount", castList(llmResult.get("fields")).size(),
                            "fields", castList(llmResult.get("fields")).stream().map(field -> field.get("key")).toList()));
            return llmResult;
        } catch (Exception e) {
            traceService.trace(planId, "ClarificationAgent", "fallback", start,
                    Map.of("message", safeSnippet(message)),
                    Map.of("provider", "mimo", "mode", "fallback", "reason", safeReason(e),
                            "fieldCount", castList(fallback.get("fields")).size()));
            return fallback;
        }
    }

    private Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", string(raw.getOrDefault("message", "还需要补充几项信息，补齐后我再查询真实地点并生成方案。")));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> field : castList(raw.get("fields"))) {
            String key = string(field.get("key"));
            if (!List.of("location", "timeWindow", "duration", "group", "budget", "preferences").contains(key)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("key", key);
            normalized.put("label", string(field.getOrDefault("label", defaultLabel(key))));
            normalized.put("question", string(field.getOrDefault("question", defaultQuestion(key))));
            normalized.put("type", string(field.getOrDefault("type", defaultType(key))));
            normalized.put("suggestions", normalizeSuggestions(field.get("suggestions"), key));
            normalized.put("allowCustom", true);
            normalized.put("reason", string(field.getOrDefault("reason", "")));
            fields.add(normalized);
        }
        result.put("fields", fields);
        result.put("missingFields", fields.stream().map(field -> field.get("key")).toList());
        return result;
    }

    private List<String> normalizeSuggestions(Object value, String key) {
        List<String> suggestions = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = string(item);
                if (!text.isBlank() && !suggestions.contains(text)) {
                    suggestions.add(text);
                }
            }
        }
        if (suggestions.isEmpty() && !"location".equals(key)) {
            suggestions.addAll(defaultSuggestions(key));
        }
        return suggestions.stream().limit(3).toList();
    }

    private List<String> defaultSuggestions(String key) {
        return switch (key) {
            case "location" -> List.of();
            case "timeWindow" -> List.of("10:00", "14:00", "19:00");
            case "duration" -> List.of("2小时左右", "3小时左右", "4小时左右");
            case "group" -> List.of("情侣两人", "2-4个朋友", "两个大人一个孩子");
            case "budget" -> List.of("300", "600", "1000");
            case "preferences" -> List.of("轻松逛逛和吃饭", "文化展览和咖啡", "室内活动和简餐");
            default -> List.of();
        };
    }

    private String defaultLabel(String key) {
        return switch (key) {
            case "location" -> "地点";
            case "timeWindow" -> "开始时间";
            case "duration" -> "游玩时长";
            case "group" -> "同行人";
            case "budget" -> "预算";
            case "preferences" -> "核心需求";
            default -> key;
        };
    }

    private String defaultQuestion(String key) {
        return switch (key) {
            case "location" -> "你想在哪个城市、商圈、地标或具体地址附近安排？";
            case "timeWindow" -> "具体几点开始？";
            case "duration" -> "大概想玩多久，或者最晚几点结束？";
            case "group" -> "几个人同行？同行人构成是什么？";
            case "budget" -> "总预算大概是多少？";
            case "preferences" -> "这次最想满足什么需求？";
            default -> "请补充这个信息。";
        };
    }

    private String defaultType(String key) {
        return "budget".equals(key) ? "number" : "timeWindow".equals(key) ? "choice" : "text";
    }

    private String extractJsonObject(String content) {
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("ClarificationAgent 输出不是 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeSnippet(String message) {
        if (message == null) return "";
        return message.length() <= 80 ? message : message.substring(0, 80);
    }

    private String safeReason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
