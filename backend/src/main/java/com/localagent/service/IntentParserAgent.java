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
            你是本地生活规划助手。请从用户输入中提取结构化意图，只输出 JSON，不要输出解释。
            字段：scenario、group、time_window、location、hard_constraints、soft_preferences、requestedPlanCount、requestedStopCount。
            不要替用户补默认时间、预算、人数或游玩时长；缺失字段用 null。
            """;

    private final MimoClient mimoClient;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;

    public IntentParserAgent(MimoClient mimoClient, ToolTraceService traceService, ObjectMapper objectMapper) {
        this.mimoClient = mimoClient;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> fastParse(UUID planId, String message) {
        long start = System.currentTimeMillis();
        Map<String, Object> intent = keywordFallback(message);
        traceService.trace(planId, "IntentParserAgent", "ok", start,
                Map.of("message", safeSnippet(message)),
                Map.of("provider", "local", "mode", "rule", "scenario", intent.get("scenario")));
        return intent;
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
                    Map.of("provider", "mimo", "mode", "fallback", "reason", safeReason(e), "scenario", fallback.get("scenario")));
            return fallback;
        }
    }

    Map<String, Object> keywordFallback(String message) {
        String text = message == null ? "" : message;
        boolean family = containsAny(text, "孩子", "小孩", "儿童", "亲子", "家庭", "老婆");
        boolean friends = containsAny(text, "朋友", "好友", "同学", "同事", "聚会");
        boolean lowCal = containsAny(text, "减肥", "低卡", "清淡", "轻食");
        boolean nearby = containsAny(text, "附近", "不要太远", "别离家太远", "步行距离短");

        Map<String, Object> group = new LinkedHashMap<>();
        Integer groupTotal = extractGroupTotal(text, family, friends);
        group.put("total", groupTotal);
        group.put("composition", groupComposition(text, family, friends, groupTotal));
        group.put("childAge", family ? 5 : null);

        List<String> hard = new ArrayList<>();
        if (family) hard.add("儿童友好");
        if (nearby) hard.add("距离近");
        if (lowCal) hard.add("低卡优先");

        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("scenario", family ? "family" : friends ? "friends" : "unknown");
        intent.put("group", group);
        intent.put("time_window", timeWindow(text));
        intent.put("location", location(text, nearby));
        intent.put("hard_constraints", hard);
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("budget", extractBudgetLevel(text));
        preferences.put("budgetAmount", extractBudgetAmount(text));
        preferences.put("vibe", family ? "亲子轻松" : friends ? "轻松社交" : "轻松");
        intent.put("soft_preferences", preferences);
        intent.put("requestedPlanCount", extractRequestedPlanCount(text));
        intent.put("requestedStopCount", extractRequestedStopCount(text));
        return intent;
    }

    private Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> intent = new LinkedHashMap<>(raw);
        intent.putIfAbsent("scenario", "unknown");
        intent.putIfAbsent("group", new LinkedHashMap<>(Map.of("total", 2, "composition", "同行人")));
        intent.putIfAbsent("location", new LinkedHashMap<>(Map.of("radius", "city")));
        intent.putIfAbsent("hard_constraints", List.of());
        intent.putIfAbsent("soft_preferences", Map.of("budget", "medium", "vibe", "轻松"));
        intent.putIfAbsent("time_window", Map.of());
        return intent;
    }

    private Map<String, Object> location(String text, boolean nearby) {
        Map<String, Object> location = new LinkedHashMap<>();
        String city = extractCityHint(text);
        location.put("city", city.isBlank() ? null : city);
        location.put("district", extractLocationHint(text, city));
        location.put("radius", nearby ? "nearby" : "city");
        return location;
    }

    private Map<String, Object> timeWindow(String text) {
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        String explicitStart = extractExplicitStart(text);
        if (explicitStart != null) {
            timeWindow.put("start", explicitStart);
        }
        if (containsAny(text, "上午")) {
            timeWindow.put("period", "上午");
        } else if (containsAny(text, "晚上", "今晚")) {
            timeWindow.put("period", "晚上");
        } else if (containsAny(text, "下午", "今天")) {
            timeWindow.put("period", text.contains("今天") ? "今天" : "下午");
        }
        Integer duration = extractDurationMinutes(text);
        if (duration != null) {
            timeWindow.put("durationMinutes", duration);
        }
        return timeWindow;
    }

    private String extractExplicitStart(String text) {
        java.util.regex.Matcher dotted = java.util.regex.Pattern
                .compile("(?<!\\d)([01]?\\d|2[0-3])[.。]([0-5]\\d)(?!\\d)")
                .matcher(text);
        if (dotted.find()) {
            return String.format("%02d:%s", Integer.parseInt(dotted.group(1)), dotted.group(2));
        }
        java.util.regex.Matcher digital = java.util.regex.Pattern
                .compile("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)")
                .matcher(text);
        if (digital.find()) {
            return String.format("%02d:%s", Integer.parseInt(digital.group(1)), digital.group(2));
        }
        java.util.regex.Matcher hourOnly = java.util.regex.Pattern
                .compile("^\\s*([01]?\\d|2[0-3])\\s*$")
                .matcher(text);
        if (hourOnly.find()) {
            return String.format("%02d:00", Integer.parseInt(hourOnly.group(1)));
        }
        java.util.regex.Matcher chinese = java.util.regex.Pattern
                .compile("(上午|早上|中午|下午|晚上|今晚)?\\s*([一二两三四五六七八九十]|1[0-2]|[1-9])点(半|[0-5]?\\d分?)?(开始|出发|左右|前后)?")
                .matcher(text);
        if (!chinese.find()) {
            return null;
        }
        int hour = parseHour(chinese.group(2));
        String period = chinese.group(1) == null ? "" : chinese.group(1);
        if ((period.contains("下午") || period.contains("晚上") || period.contains("今晚")) && hour < 12) {
            hour += 12;
        }
        if (period.contains("中午") && hour < 11) {
            hour += 12;
        }
        String minuteText = chinese.group(3) == null ? "" : chinese.group(3);
        int minute = minuteText.contains("半") ? 30 : parseMinute(minuteText);
        return String.format("%02d:%02d", hour, minute);
    }

    private int parseHour(String text) {
        return switch (text) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> Integer.parseInt(text);
        };
    }

    private int parseMinute(String text) {
        String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    private Integer extractGroupTotal(String text, boolean family, boolean friends) {
        if (containsAny(text, "两个大人一个孩子", "2个大人1个孩子", "两大一小")) {
            return 3;
        }
        if (containsAny(text, "4个人", "四个人", "4个朋友", "四个朋友")) {
            return 4;
        }
        if (family && containsAny(text, "一家三口", "三口", "两大一小")) {
            return 3;
        }
        return null;
    }

    private String groupComposition(String text, boolean family, boolean friends, Integer total) {
        if (total == null) {
            return null;
        }
        if (family) {
            return "家庭亲子";
        }
        if (friends) {
            return "朋友同行";
        }
        return "同行人";
    }

    private String extractBudgetLevel(String text) {
        if (containsAny(text, "300", "400", "便宜", "省钱")) {
            return "low";
        }
        if (containsAny(text, "1000", "1500", "高预算")) {
            return "high";
        }
        return extractBudgetAmount(text) == null ? null : "medium";
    }

    private Integer extractBudgetAmount(String text) {
        String digits = text.replaceAll(".*?(\\d{2,5}).*", "$1");
        if (digits.equals(text) && !text.matches(".*\\d{2,5}.*")) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer extractDurationMinutes(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2})\\s*(个)?\\s*小时").matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1)) * 60;
        }
        return null;
    }

    private Integer extractRequestedPlanCount(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d)\\s*(套|个)?\\s*方案").matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private Integer extractRequestedStopCount(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d)\\s*(个)?\\s*(地点|地方|POI)").matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("MiMo 输出不是 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractLocationHint(String text, String city) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.contains("我附近") || text.contains("当前位置") || text.equals("附近")) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,24}(广场|公园|大学|商场|中心|车站|火车站|地铁站|机场|景区|街|路|区|县|镇|商圈))")
                .matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return city.isBlank() ? null : city;
    }

    private String extractCityHint(String text) {
        String value = text == null ? "" : text;
        java.util.regex.Matcher explicit = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5]{2,12}?市)")
                .matcher(value);
        if (explicit.find()) {
            return explicit.group(1).replace("市", "");
        }
        for (String city : List.of(
                "北京", "上海", "天津", "重庆", "广州", "深圳", "杭州", "南京", "苏州", "成都", "武汉", "西安",
                "长沙", "郑州", "青岛", "济南", "厦门", "福州", "宁波", "无锡", "合肥", "昆明", "南昌", "南宁",
                "贵阳", "太原", "石家庄", "沈阳", "长春", "哈尔滨", "大连", "珠海", "佛山", "东莞", "泉州",
                "洛阳", "海口", "三亚", "乌鲁木齐", "兰州", "银川", "西宁", "拉萨", "呼和浩特")) {
            if (value.contains(city)) {
                return city;
            }
        }
        return "";
    }

    private String safeSnippet(String message) {
        if (message == null) return "";
        return message.length() <= 80 ? message : message.substring(0, 80);
    }

    private String safeReason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
