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
            你是全国本地生活规划产品的需求分析 Agent。

            你的目标是把用户自然语言和已补充的字符串事实，整理成两层数据：
            1. userFacts：保留用户原始表达和澄清答案，必须尽量原样保存。
            2. derived：为后续高德、搜索、路线等工具派生出的结构化字段。

            必须遵守：
            - 不要替用户补默认城市、默认出发时间、默认预算、默认人数或默认时长。
            - “附近/我附近/本地/地铁站附近/我所在城市”没有坐标或城市地标时，location 不完整。
            - “上午/下午/晚上/周末/下班后/今天下午”不是明确 start。
            - 儿童、老人、行动不便、忌口、过敏、停车、地铁、排队、宠物、室内外、天气敏感属于重要管家约束。
            - 只输出 JSON 对象，不输出解释。

            输出字段：
            {
              "scenario": "family|friends|couple|solo|business|general|unknown",
              "location": {"city": null, "district": null, "radius": "nearby|city", "lng": null, "lat": null},
              "time_window": {"start": null, "end": null, "period": null, "durationMinutes": null},
              "group": {"total": null, "composition": null, "hasChildren": false, "hasElderly": false, "childAge": null},
              "hard_constraints": [],
              "soft_preferences": {"budget": null, "budgetAmount": null, "vibe": null, "queueTolerance": null, "indoorOutdoor": null},
              "requestedPlanCount": null,
              "requestedStopCount": null,
              "poiSearchStrategy": {
                "activityKeywords": [],
                "diningKeywords": [],
                "extraKeywords": [],
                "rankingWeights": {"distance": 0.3, "rating": 0.25, "budgetFit": 0.2, "scenarioFit": 0.25},
                "butlerNotes": []
              },
              "confidence": 0.0
            }
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
                    Map.of("provider", "mimo", "mode", "real", "scenario", intent.get("scenario"),
                            "confidence", intent.getOrDefault("confidence", 0)));
            return intent;
        } catch (Exception e) {
            Map<String, Object> fallback = keywordFallback(message);
            traceService.trace(planId, "IntentParserAgent", "fallback", start,
                    Map.of("message", safeSnippet(message)),
                    Map.of("provider", "mimo", "mode", "fallback", "reason", safeReason(e),
                            "scenario", fallback.get("scenario")));
            return fallback;
        }
    }

    Map<String, Object> keywordFallback(String message) {
        String text = message == null ? "" : message;
        boolean family = containsAny(text, "孩子", "小孩", "儿童", "亲子", "家庭", "宝宝");
        boolean elderly = containsAny(text, "老人", "长辈", "父母", "行动不便");
        boolean friends = containsAny(text, "朋友", "好友", "同学", "同事", "聚会", "团建");
        boolean couple = containsAny(text, "情侣", "约会", "对象", "女朋友", "男朋友", "夫妻");
        boolean solo = containsAny(text, "我自己", "一个人", "独自", "单人");
        boolean lowCal = containsAny(text, "减肥", "低卡", "清淡", "轻食", "不吃辣", "忌口", "过敏");
        boolean nearby = containsAny(text, "附近", "不要太远", "别离家太远", "步行距离短", "少走路");

        Map<String, Object> group = new LinkedHashMap<>();
        Integer groupTotal = extractGroupTotal(text, family, friends, couple, solo);
        group.put("total", groupTotal);
        group.put("composition", groupComposition(text, family, friends, couple, solo, groupTotal));
        group.put("hasChildren", family);
        group.put("hasElderly", elderly);
        group.put("childAge", family ? extractChildAge(text) : null);

        List<String> hard = new ArrayList<>();
        if (family) hard.add("儿童友好");
        if (elderly) hard.add("老人友好");
        if (nearby) hard.add("低步行");
        if (lowCal) hard.add("饮食限制");
        if (containsAny(text, "宠物", "狗", "猫")) hard.add("宠物友好");
        if (containsAny(text, "停车", "开车")) hard.add("停车便利");
        if (containsAny(text, "地铁", "公交")) hard.add("公共交通便利");

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("budget", extractBudgetLevel(text));
        preferences.put("budgetAmount", extractBudgetAmount(text));
        preferences.put("vibe", extractVibe(text, family, friends, couple, solo));
        preferences.put("queueTolerance", containsAny(text, "不要排队", "少排队", "排队少") ? "low" : null);
        preferences.put("indoorOutdoor", containsAny(text, "室内", "下雨", "太热", "太冷") ? "indoor_preferred"
                : containsAny(text, "户外", "公园", "露营") ? "outdoor_preferred" : null);

        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("scenario", family ? "family" : friends ? "friends" : couple ? "couple" : solo ? "solo" : "unknown");
        intent.put("group", group);
        intent.put("time_window", timeWindow(text));
        intent.put("location", location(text, nearby));
        intent.put("hard_constraints", hard);
        intent.put("soft_preferences", preferences);
        intent.put("requestedPlanCount", extractRequestedPlanCount(text));
        intent.put("requestedStopCount", extractRequestedStopCount(text));
        intent.put("poiSearchStrategy", defaultPoiSearchStrategy(intent));
        intent.put("confidence", 0.55);
        return normalize(intent);
    }

    private Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> intent = new LinkedHashMap<>(raw == null ? Map.of() : raw);
        intent.putIfAbsent("scenario", "unknown");
        intent.put("location", mutableMap(intent.get("location")));
        intent.put("group", mutableMap(intent.get("group")));
        intent.put("time_window", mutableMap(intent.get("time_window")));
        intent.put("soft_preferences", mutableMap(intent.get("soft_preferences")));
        intent.putIfAbsent("hard_constraints", List.of());
        intent.putIfAbsent("confidence", 0.0);
        intent.put("poiSearchStrategy", normalizeStrategy(intent.get("poiSearchStrategy"), intent));
        return intent;
    }

    private Map<String, Object> normalizeStrategy(Object value, Map<String, Object> intent) {
        Map<String, Object> strategy = value instanceof Map<?, ?> ? mutableMap(value) : defaultPoiSearchStrategy(intent);
        strategy.put("activityKeywords", stringListOrDefault(strategy.get("activityKeywords"),
                castStringList(defaultPoiSearchStrategy(intent).get("activityKeywords"))));
        strategy.put("diningKeywords", stringListOrDefault(strategy.get("diningKeywords"),
                castStringList(defaultPoiSearchStrategy(intent).get("diningKeywords"))));
        strategy.put("extraKeywords", stringListOrDefault(strategy.get("extraKeywords"),
                castStringList(defaultPoiSearchStrategy(intent).get("extraKeywords"))));
        strategy.putIfAbsent("rankingWeights", Map.of("distance", 0.3, "rating", 0.25, "budgetFit", 0.2, "scenarioFit", 0.25));
        strategy.putIfAbsent("butlerNotes", List.of());
        return strategy;
    }

    private Map<String, Object> defaultPoiSearchStrategy(Map<String, Object> intent) {
        String scenario = String.valueOf(intent.getOrDefault("scenario", "unknown"));
        List<String> hard = castStringList(intent.get("hard_constraints"));
        Map<String, Object> preferences = mutableMap(intent.get("soft_preferences"));
        String vibe = String.valueOf(preferences.getOrDefault("vibe", ""));

        List<String> activity = new ArrayList<>();
        List<String> dining = new ArrayList<>();
        List<String> extra = new ArrayList<>(List.of("咖啡", "书店", "公园"));
        List<String> notes = new ArrayList<>();

        if ("family".equals(scenario) || hard.contains("儿童友好")) {
            activity.addAll(List.of("亲子", "儿童乐园", "博物馆", "科技馆"));
            dining.addAll(List.of("亲子餐厅", "儿童友好餐厅", "家庭餐厅"));
            notes.add("优先儿童友好、少排队、卫生间和休息点明确的地点。");
        } else if ("friends".equals(scenario)) {
            activity.addAll(List.of("桌游", "密室", "KTV", "展览"));
            dining.addAll(List.of("聚餐", "烧烤", "火锅", "餐厅"));
        } else if ("couple".equals(scenario)) {
            activity.addAll(List.of("展览", "咖啡", "夜景", "艺术馆"));
            dining.addAll(List.of("约会餐厅", "西餐", "日料", "清淡餐厅"));
        } else {
            activity.addAll(List.of("展览", "文化", "公园", "娱乐"));
            dining.addAll(List.of("餐厅", "简餐", "清淡餐厅"));
        }
        if (hard.contains("老人友好") || hard.contains("低步行")) {
            activity.add(0, "室内");
            extra.add(0, "商场");
            notes.add("控制步行距离，优先地铁/停车便利和可休息地点。");
        }
        if (hard.contains("饮食限制") || vibe.contains("清淡") || vibe.contains("低卡")) {
            dining.add(0, "轻食");
            dining.add(1, "健康餐");
            notes.add("饮食限制优先，避免重口味和高油高辣。");
        }
        if ("indoor_preferred".equals(preferences.get("indoorOutdoor"))) {
            activity.add(0, "室内活动");
            notes.add("天气敏感，优先室内地点。");
        }

        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("activityKeywords", activity.stream().distinct().limit(6).toList());
        strategy.put("diningKeywords", dining.stream().distinct().limit(6).toList());
        strategy.put("extraKeywords", extra.stream().distinct().limit(5).toList());
        strategy.put("rankingWeights", Map.of("distance", 0.35, "rating", 0.25, "budgetFit", 0.15, "scenarioFit", 0.25));
        strategy.put("butlerNotes", notes);
        return strategy;
    }

    private Map<String, Object> location(String text, boolean nearby) {
        Map<String, Object> location = new LinkedHashMap<>();
        double[] coordinates = parseCoordinates(text);
        if (coordinates != null) {
            location.put("city", extractCityHint(text));
            location.put("district", text.contains("当前位置") ? "当前位置" : extractLocationHint(text, extractCityHint(text)));
            location.put("lng", coordinates[0]);
            location.put("lat", coordinates[1]);
            location.put("radius", "nearby");
            return location;
        }
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
        } else if (containsAny(text, "上午", "下午", "晚上", "今晚", "周末", "下班后")) {
            timeWindow.put("invalidReason", "时间表达还不够明确");
        }
        if (containsAny(text, "上午")) timeWindow.put("period", "上午");
        else if (containsAny(text, "晚上", "今晚")) timeWindow.put("period", "晚上");
        else if (containsAny(text, "下午")) timeWindow.put("period", "下午");
        Integer duration = extractDurationMinutes(text);
        if (duration != null) timeWindow.put("durationMinutes", duration);
        return timeWindow;
    }

    private String extractExplicitStart(String text) {
        java.util.regex.Matcher digital = java.util.regex.Pattern
                .compile("(?<!\\d)([01]?\\d|2[0-3])\\s*[:.：]\\s*([0-5]\\d)(?!\\d)")
                .matcher(text);
        if (digital.find()) return String.format("%02d:%s", Integer.parseInt(digital.group(1)), digital.group(2));
        java.util.regex.Matcher chinese = java.util.regex.Pattern
                .compile("(上午|早上|中午|下午|晚上|今晚)?\\s*([一二两三四五六七八九十]|1[0-2]|[1-9])\\s*点\\s*(半|[0-5]?\\d分?)?(开始|出发|左右|前后)?")
                .matcher(text);
        if (!chinese.find()) return null;
        int hour = parseHour(chinese.group(2));
        String period = chinese.group(1) == null ? "" : chinese.group(1);
        if ((period.contains("下午") || period.contains("晚上") || period.contains("今晚")) && hour < 12) hour += 12;
        if (period.contains("中午") && hour < 11) hour += 12;
        String minuteText = chinese.group(3) == null ? "" : chinese.group(3);
        int minute = minuteText.contains("半") ? 30 : parseMinute(minuteText);
        return String.format("%02d:%02d", hour, minute);
    }

    private Integer extractGroupTotal(String text, boolean family, boolean friends, boolean couple, boolean solo) {
        if (solo) return 1;
        if (couple) return 2;
        if (containsAny(text, "两个大人一个孩子", "2个大人1个孩子", "两大一小", "一家三口", "三口")) return 3;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2})\\s*(个)?\\s*(人|朋友|同事|同学)").matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        if (family || friends) return null;
        return null;
    }

    private String groupComposition(String text, boolean family, boolean friends, boolean couple, boolean solo, Integer total) {
        if (solo) return "单人";
        if (couple) return "情侣/夫妻";
        if (family && total != null) return "家庭亲子";
        if (friends && total != null) return "朋友同行";
        return null;
    }

    private Integer extractChildAge(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2})\\s*岁").matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        return null;
    }

    private String extractBudgetLevel(String text) {
        Integer amount = extractBudgetAmount(text);
        if (containsAny(text, "便宜", "省钱", "低预算")) return "low";
        if (containsAny(text, "高预算", "贵一点", "品质")) return "high";
        if (amount == null) return null;
        return amount < 500 ? "low" : amount > 1000 ? "high" : "medium";
    }

    private Integer extractBudgetAmount(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{2,5})\\s*(元|块|预算)?").matcher(text);
        if (!matcher.find()) return null;
        return Integer.parseInt(matcher.group(1));
    }

    private String extractVibe(String text, boolean family, boolean friends, boolean couple, boolean solo) {
        if (containsAny(text, "亲子", "孩子", "儿童")) return "亲子友好";
        if (containsAny(text, "清淡", "低卡", "轻食")) return "清淡健康";
        if (containsAny(text, "展览", "文化", "博物馆")) return "文化展览";
        if (containsAny(text, "安静", "少人", "不吵")) return "安静轻松";
        if (family) return "亲子轻松";
        if (friends) return "轻松社交";
        if (couple) return "约会氛围";
        if (solo) return "单人放松";
        return null;
    }

    private Integer extractDurationMinutes(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2})\\s*(个)?\\s*小时").matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1)) * 60;
        if (text.contains("半天")) return 4 * 60;
        return null;
    }

    private Integer extractRequestedPlanCount(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d)\\s*(套|个)?\\s*方案").matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        return null;
    }

    private Integer extractRequestedStopCount(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d)\\s*(个)?\\s*(地点|地方|POI)").matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        return null;
    }

    private String extractLocationHint(String text, String city) {
        if (text == null || text.isBlank()) return null;
        if (containsAny(text, "我附近", "当前位置") || "附近".equals(text.trim())) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,24}(广场|公园|大学|商场|中心|车站|火车站|地铁站|机场|景区|街|路|区|县|镇|商圈|湖|馆))")
                .matcher(text);
        if (matcher.find()) return matcher.group(1);
        return city == null || city.isBlank() ? null : city;
    }

    private String extractCityHint(String text) {
        String value = text == null ? "" : text;
        java.util.regex.Matcher explicit = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5]{2,12}?)(市)")
                .matcher(value);
        if (explicit.find()) return explicit.group(1);
        for (String city : List.of(
                "北京", "上海", "天津", "重庆", "广州", "深圳", "杭州", "南京", "苏州", "成都", "武汉", "西安",
                "长沙", "郑州", "青岛", "济南", "厦门", "福州", "宁波", "无锡", "合肥", "昆明", "南昌", "南宁",
                "贵阳", "太原", "石家庄", "沈阳", "长春", "哈尔滨", "大连", "珠海", "佛山", "东莞", "泉州",
                "洛阳", "海口", "三亚", "乌鲁木齐", "兰州", "银川", "西宁", "拉萨", "呼和浩特")) {
            if (value.contains(city)) return city;
        }
        return "";
    }

    private double[] parseCoordinates(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(-?\\d{2,3}\\.\\d{3,})\\s*[,，\\s]\\s*(-?\\d{1,2}\\.\\d{3,})")
                .matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        try {
            double lng = Double.parseDouble(matcher.group(1));
            double lat = Double.parseDouble(matcher.group(2));
            if (lng < 73 || lng > 136 || lat < 3 || lat > 54) return null;
            return new double[] {lng, lat};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractJsonObject(String content) {
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("MiMo 输出不是 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        return value instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }

    private List<String> stringListOrDefault(Object value, List<String> fallback) {
        List<String> parsed = castStringList(value);
        return parsed.isEmpty() ? fallback : parsed;
    }

    private List<String> castStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).distinct().toList();
    }

    private boolean containsAny(String text, String... keywords) {
        String value = text == null ? "" : text;
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
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
        return digits.isBlank() ? 0 : Integer.parseInt(digits);
    }

    private String safeSnippet(String message) {
        if (message == null) return "";
        return message.length() <= 80 ? message : message.substring(0, 80);
    }

    private String safeReason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
