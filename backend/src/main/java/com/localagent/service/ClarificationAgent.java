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
    private static final List<String> ALLOWED_KEYS = List.of(
            "location", "timeWindow", "duration", "group", "budget", "preferences"
    );

    private static final String SYSTEM_PROMPT = """
            你是全国本地生活规划产品的需求澄清 Agent。

            你的任务不是生成行程，而是在调用地图、搜索、路线、天气等真实工具之前，判断用户输入和已收集事实还缺少哪些必要条件，并生成适合前端展示的澄清卡片。

            业务底线：
            1. 只要缺少可定位地点、明确开始时间、游玩时长/结束时间、同行人构成、预算、核心需求，就必须继续澄清。
            2. “附近”“我附近”“本地”“我所在城市”“地铁站附近”没有坐标或具体城市地标时，不算可定位地点。
            3. “上午/下午/晚上/周末/下班后/今天下午”不算明确开始时间，必须让用户补成 10:00、14:30、晚上7点这类表达。
            4. 用户补充答案可以是自然语言字符串。不要要求用户按机器格式填写。
            5. 不要编造城市、商圈、POI、营业状态、价格或天气。
            6. 对儿童、老人、行动不便、过敏、忌口、停车、地铁、宠物、排队容忍度、室内外、母婴室/厕所等信息要有管家意识：若它们影响安全或体验，就在问题或选项里提醒用户补充。

            输出要求：
            - 只输出 JSON 对象，不输出解释。
            - 只问真正缺失或无效的字段，字段 key 只能是 location、timeWindow、duration、group、budget、preferences。
            - 每个字段 type 固定为 "text"。
            - 每个字段必须有 0 到 3 个 suggestions；有 suggestions 时同时给 options，options 形如 [{"code":"A","text":"..."}]。
            - 每个字段必须 allowCustom=true，必须有 reason 和 expectedAnswerHint。
            - location 如果不知道用户城市，suggestions 必须为空，不要给“我所在城市 + ...”这种模板选项。

            JSON 结构：
            {
              "message": "一句中文提示",
              "missingFields": ["location"],
              "fields": [
                {
                  "key": "location",
                  "label": "地点",
                  "question": "中文问题",
                  "type": "text",
                  "suggestions": ["选项A", "选项B", "选项C"],
                  "options": [{"code":"A","text":"选项A"}],
                  "allowCustom": true,
                  "reason": "为什么缺这个字段",
                  "expectedAnswerHint": "用户可以怎么填"
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
                    "ruleDetectedClarification", fallback
            ));
            String content = mimoClient.complete(SYSTEM_PROMPT, userPrompt);
            Map<String, Object> llmResult = normalize(objectMapper.readValue(extractJsonObject(content), new TypeReference<>() {}), fallback);
            if (castList(llmResult.get("fields")).isEmpty()) {
                return Map.of();
            }
            traceService.trace(planId, "ClarificationAgent", "ok", start,
                    Map.of("message", safeSnippet(message), "fallbackFieldCount", castList(fallback.get("fields")).size()),
                    Map.of("provider", "mimo", "mode", "real", "fieldCount", castList(llmResult.get("fields")).size(),
                            "fields", castList(llmResult.get("fields")).stream().map(field -> field.get("key")).toList()));
            return llmResult;
        } catch (Exception e) {
            Map<String, Object> normalizedFallback = normalize(fallback, fallback);
            traceService.trace(planId, "ClarificationAgent", "fallback", start,
                    Map.of("message", safeSnippet(message)),
                    Map.of("provider", "mimo", "mode", "fallback", "reason", safeReason(e),
                            "fieldCount", castList(normalizedFallback.get("fields")).size()));
            return normalizedFallback;
        }
    }

    private Map<String, Object> normalize(Map<String, Object> raw, Map<String, Object> fallback) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", string(raw.getOrDefault("message",
                fallback == null ? "还需要补充几项信息，补齐后我再查询真实地点并生成方案。" : fallback.get("message"))));

        List<Map<String, Object>> fallbackFields = castList(fallback == null ? null : fallback.get("fields"));
        Map<String, Map<String, Object>> fallbackByKey = new LinkedHashMap<>();
        fallbackFields.forEach(field -> fallbackByKey.put(string(field.get("key")), field));

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> field : castList(raw.get("fields"))) {
            String key = string(field.get("key"));
            if (!ALLOWED_KEYS.contains(key)) continue;
            Map<String, Object> fallbackField = fallbackByKey.getOrDefault(key, Map.of());
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("key", key);
            normalized.put("label", firstText(field.get("label"), fallbackField.get("label"), defaultLabel(key)));
            normalized.put("question", firstText(field.get("question"), fallbackField.get("question"), defaultQuestion(key)));
            normalized.put("type", "text");
            List<String> suggestions = normalizeSuggestions(field.get("suggestions"), fallbackField.get("suggestions"), key);
            normalized.put("suggestions", suggestions);
            normalized.put("options", normalizeOptions(field.get("options"), suggestions));
            normalized.put("allowCustom", true);
            normalized.put("reason", firstText(field.get("reason"), fallbackField.get("reason"), defaultReason(key)));
            normalized.put("expectedAnswerHint", firstText(field.get("expectedAnswerHint"), fallbackField.get("expectedAnswerHint"),
                    defaultHint(key)));
            fields.add(normalized);
        }
        if (fields.isEmpty() && !fallbackFields.isEmpty()) {
            for (Map<String, Object> field : fallbackFields) {
                String key = string(field.get("key"));
                if (ALLOWED_KEYS.contains(key)) {
                    Map<String, Object> normalized = new LinkedHashMap<>(field);
                    List<String> suggestions = normalizeSuggestions(field.get("suggestions"), null, key);
                    normalized.put("type", "text");
                    normalized.put("suggestions", suggestions);
                    normalized.put("options", normalizeOptions(field.get("options"), suggestions));
                    normalized.put("allowCustom", true);
                    normalized.putIfAbsent("reason", defaultReason(key));
                    normalized.putIfAbsent("expectedAnswerHint", defaultHint(key));
                    fields.add(normalized);
                }
            }
        }
        result.put("fields", fields);
        result.put("missingFields", fields.stream().map(field -> field.get("key")).toList());
        return result;
    }

    private List<String> normalizeSuggestions(Object primary, Object fallback, String key) {
        List<String> suggestions = new ArrayList<>();
        addSuggestions(suggestions, primary);
        if (suggestions.isEmpty()) {
            addSuggestions(suggestions, fallback);
        }
        if (suggestions.isEmpty() && !"location".equals(key)) {
            suggestions.addAll(defaultSuggestions(key));
        }
        return suggestions.stream().distinct().limit(3).toList();
    }

    private void addSuggestions(List<String> suggestions, Object value) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = string(item);
                if (!text.isBlank()) suggestions.add(text);
            }
        }
    }

    private List<Map<String, Object>> normalizeOptions(Object value, List<String> suggestions) {
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

    private List<String> defaultSuggestions(String key) {
        return switch (key) {
            case "timeWindow" -> List.of("10:00", "14:00", "19:00");
            case "duration" -> List.of("2小时左右", "3小时左右", "4小时左右");
            case "group" -> List.of("我自己", "情侣两人", "两个大人一个孩子");
            case "budget" -> List.of("300元", "600元", "1000元");
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

    private String defaultReason(String key) {
        return switch (key) {
            case "location" -> "地图搜索需要可定位地点。";
            case "timeWindow" -> "明确开始时间会影响营业、天气和路线。";
            case "duration" -> "时长决定行程密度。";
            case "group" -> "同行人影响安全和体验筛选。";
            case "budget" -> "预算影响餐厅和活动匹配。";
            case "preferences" -> "核心需求决定搜索策略。";
            default -> "补齐后才能规划。";
        };
    }

    private String defaultHint(String key) {
        return switch (key) {
            case "location" -> "城市 + 地标/商圈/地址，或当前位置经纬度。";
            case "timeWindow" -> "10:00、14:30、晚上7点。";
            case "duration" -> "3小时左右、晚饭后结束。";
            case "group" -> "情侣两人、两个大人一个孩子、4个朋友。";
            case "budget" -> "总预算600元、每人200左右。";
            case "preferences" -> "亲子、约会、少走路、清淡、室内等。";
            default -> "自然语言填写即可。";
        };
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

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = string(value);
            if (!text.isBlank()) return text;
        }
        return "";
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
