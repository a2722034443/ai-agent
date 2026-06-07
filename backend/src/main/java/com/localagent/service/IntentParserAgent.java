package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
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
            MimoClient.CompletionResult completion = mimoClient.completeWithMeta(SYSTEM_PROMPT, message == null ? "" : message);
            String content = completion.content();
            Map<String, Object> intent = normalize(objectMapper.readValue(extractJsonObject(content), new TypeReference<>() {}));
            traceService.trace(planId, "IntentParserAgent", "ok", start,
                    Map.of("message", safeSnippet(message)),
                    Map.of("provider", "mimo", "mode", "real", "lane", completion.lane(),
                            "model", completion.model(), "llmDurationMs", completion.durationMs(),
                            "fallbackReason", completion.fallbackReason(), "scenario", intent.get("scenario"),
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
        boolean friends = containsAny(text, "朋友", "好友", "同学", "同事", "聚会", "团建");
        boolean couple = containsAny(text, "情侣", "约会", "对象", "女朋友", "男朋友", "夫妻");
        boolean family = familySignal(text);
        boolean elderly = containsAny(text, "老人", "长辈", "父母", "爸妈", "妈妈", "爸爸", "行动不便");
        boolean solo = containsAny(text, "我自己", "一个人", "独自", "单人", "无同行人", "没有同行人", "就我", "只有我")
                || (!family && !friends && !couple && containsAny(text, "我现在在", "我在", "我周末去", "我今天", "我想", "我想去", "我去", "只有3小时", "只有 3 小时"));
        boolean lowCal = containsAny(text, "减肥", "低卡", "清淡", "轻食", "不吃辣", "忌口", "过敏");
        boolean nearby = containsAny(text, "附近", "不要太远", "别离家太远", "步行距离短", "少走路", "顺路", "不要绕路", "不用绕");

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
        if (containsAny(text, "全程步行", "步行就可以", "不想坐车", "不要坐车", "不用坐车", "只步行")) hard.add("全程步行");
        if (containsAny(text, "临时闭馆", "闭馆", "关门", "关闭")) hard.add("POI不可用需替换");
        if (containsAny(text, "排队要1小时", "排队1小时", "排队太久", "排队过长")) hard.add("排队过长需替换");
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
        intent.put("scenario", friends ? "friends" : family ? "family" : couple ? "couple" : solo ? "solo" : "unknown");
        intent.put("group", group);
        intent.put("time_window", timeWindow(text));
        Map<String, Object> location = location(text, nearby);
        Map<String, Object> locationTrust = locationTrust(location, text);
        location.put("locationTrust", locationTrust);
        intent.put("location", location);
        intent.put("locationTrust", locationTrust);
        List<String> citySignals = extractCityMentions(text);
        intent.put("citySignals", citySignals);
        Map<String, Object> multiOrigin = extractMultiOrigin(text);
        if (!multiOrigin.isEmpty()) {
            intent.put("multiOrigin", multiOrigin);
        }
        intent.put("hard_constraints", hard);
        intent.put("soft_preferences", preferences);
        intent.put("requestedPlanCount", extractRequestedPlanCount(text));
        intent.put("requestedStopCount", extractRequestedStopCount(text));
        List<Map<String, Object>> explicitPois = filterOriginOnlyPois(extractExplicitPois(text), multiOrigin, text);
        intent.put("explicitPois", filterNonDestinationExplicitPois(explicitPois, text));
        intent.put("executionRequests", extractExecutionRequests(text));
        intent.put("rawMessage", text);
        intent.put("poiSearchStrategy", defaultPoiSearchStrategy(intent));
        intent.put("confidence", 0.55);
        return normalize(intent);
    }

    private boolean familySignal(String text) {
        String value = text == null ? "" : text;
        if (containsAny(value, "孩子", "小孩", "儿童", "亲子", "宝宝", "爸妈", "父母", "一家三口", "两大一小", "2大1小")) {
            return true;
        }
        String withoutPoiNames = value.replaceAll("家庭[\\u4e00-\\u9fa5A-Za-z0-9・·\\s()（）-]{0,16}(餐厅|海鲜|饭店|菜馆|店)", "");
        return containsAny(withoutPoiNames, "家庭出游", "家庭游", "家庭聚会", "家庭聚餐", "家庭");
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
        List<String> explicitPoiNames = explicitPoiNames(intent.get("explicitPois"));
        Map<String, Object> preferences = mutableMap(intent.get("soft_preferences"));
        String vibe = String.valueOf(preferences.getOrDefault("vibe", ""));
        String sourceText = vibe + " " + String.valueOf(intent.getOrDefault("rawMessage", ""));

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
        addIfMentioned(sourceText, activity, "大连世界博览广场", "大连世界博览广场", "世界博览广场");
        addIfMentioned(sourceText, activity, "上海博物馆", "上海博物馆");
        addIfMentioned(sourceText, activity, "人民公园", "人民公园");
        addIfMentioned(sourceText, activity, "故宫", "故宫");
        addIfMentioned(sourceText, activity, "景山公园", "景山公园");
        addIfMentioned(sourceText, activity, "岳王庙", "岳王庙");
        addIfMentioned(sourceText, activity, "曲院风荷", "曲院风荷");
        addIfMentioned(sourceText, activity, "苏堤", "苏堤");
        addIfMentioned(sourceText, activity, "断桥", "断桥");
        addIfMentioned(sourceText, dining, "海鲜", "海鲜", "海鲜餐");
        addIfMentioned(sourceText, dining, "北京烤鸭", "北京烤鸭", "烤鸭");
        addIfMentioned(sourceText, dining, "杭帮菜", "杭帮菜");
        addIfMentioned(sourceText, dining, "小杨生煎", "小杨生煎", "生煎");
        addIfMentioned(sourceText, extra, "咖啡", "咖啡");
        addIfMentioned(sourceText, extra, "老北京酸奶", "老北京酸奶", "酸奶");
        addIfMentioned(sourceText, extra, "龙井", "龙井", "茶");
        for (String explicitPoi : explicitPoiNames) {
            if (containsAny(explicitPoi, "餐厅", "海鲜", "火锅", "烤肉", "饭店", "料理", "小吃")) {
                dining.add(0, explicitPoi);
            } else if (containsAny(explicitPoi, "咖啡", "茶", "酸奶", "甜品")) {
                extra.add(0, explicitPoi);
            } else {
                activity.add(0, explicitPoi);
            }
        }
        if (hard.contains("老人友好") || hard.contains("低步行")) {
            activity.add("室内");
            extra.add("商场");
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
        if (vibe.contains("电影")) {
            activity.add(0, "电影院");
        }
        if (vibe.contains("海边") || vibe.contains("海滨") || vibe.contains("沙滩")) {
            extra.add(0, "海滨公园");
            extra.add(1, "海边");
        }
        if (vibe.contains("烤肉") || vibe.contains("烧烤")) {
            dining.add(0, "烤肉");
            dining.add(1, "烧烤");
        }

        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("activityKeywords", activity.stream().distinct().limit(6).toList());
        strategy.put("diningKeywords", dining.stream().distinct().limit(6).toList());
        strategy.put("extraKeywords", extra.stream().distinct().limit(5).toList());
        strategy.put("rankingWeights", Map.of("distance", 0.35, "rating", 0.25, "budgetFit", 0.15, "scenarioFit", 0.25));
        strategy.put("butlerNotes", notes);
        return strategy;
    }

    private void addIfMentioned(String text, List<String> target, String keyword, String... mentions) {
        if (containsAny(text, mentions)) {
            target.add(0, keyword);
        }
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
        String normalized = text == null ? "" : text.trim();
        if (isPlaceholderNearbyLocation(normalized)) {
            location.put("city", null);
            location.put("district", null);
            location.put("radius", "nearby");
            return location;
        }
        String city = extractCityHint(text);
        location.put("city", city.isBlank() ? null : city);
        location.put("district", extractLocationHint(text, city));
        location.put("radius", nearby ? "nearby" : "city");
        return location;
    }

    private Map<String, Object> locationTrust(Map<String, Object> location, String text) {
        Map<String, Object> trust = new LinkedHashMap<>();
        boolean hasCoordinate = location.get("lng") instanceof Number && location.get("lat") instanceof Number;
        boolean hasCity = location.get("city") != null && !String.valueOf(location.get("city")).isBlank();
        boolean hasDistrict = location.get("district") != null && !String.valueOf(location.get("district")).isBlank();
        if (hasCoordinate) {
            trust.put("level", "coordinate");
            trust.put("confidence", 0.92);
            trust.put("reason", "用户文本包含可校验的中国经纬度坐标。");
        } else if (hasCity && hasDistrict) {
            trust.put("level", "city_landmark");
            trust.put("confidence", 0.76);
            trust.put("reason", "已识别城市和地标，坐标由地图服务或演示映射补足。");
        } else if (hasDistrict) {
            trust.put("level", "landmark_only");
            trust.put("confidence", 0.62);
            trust.put("reason", "识别到地标但城市不完整，生成方案前需要谨慎核验。");
        } else {
            trust.put("level", "unknown");
            trust.put("confidence", 0.25);
            trust.put("reason", containsAny(text, "附近", "当前位置") ? "用户使用附近/当前位置表达，但没有提供可用坐标。" : "缺少明确城市或地标。");
        }
        return trust;
    }

    private List<String> extractCityMentions(String text) {
        String value = text == null ? "" : text;
        List<CityReference> references = new ArrayList<>();
        java.util.regex.Matcher explicit = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5]{2,12}?)(市)")
                .matcher(value);
        while (explicit.find()) {
            references.add(new CityReference(explicit.group(1), explicit.start(1)));
        }
        for (String city : knownCities()) {
            int from = 0;
            while (from < value.length()) {
                int index = value.indexOf(city, from);
                if (index < 0) {
                    break;
                }
                if (isExplicitCityMention(value, index, city)) {
                    references.add(new CityReference(city, index));
                }
                from = index + city.length();
            }
        }
        references.sort(Comparator.comparingInt(CityReference::index));
        List<String> cities = new ArrayList<>();
        for (CityReference reference : references) {
            if (!cities.contains(reference.name())) {
                cities.add(reference.name());
            }
        }
        return cities;
    }

    private Map<String, Object> extractMultiOrigin(String text) {
        String value = text == null ? "" : text;
        List<Map<String, Object>> participants = new ArrayList<>();
        List<String> overallCities = extractCityMentions(value);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(我|朋友[A-Za-z0-9一二三四五六七八九十]?|好友[A-Za-z0-9一二三四五六七八九十]?|同事[A-Za-z0-9一二三四五六七八九十]?)[^，,。；;]{0,4}在([^，,。；;]+)")
                .matcher(value);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String origin = cleanOriginName(matcher.group(2));
            if (origin.isBlank() || origin.contains("汇合") || origin.contains("碰面")) {
                continue;
            }
            boolean exists = participants.stream().anyMatch(item -> name.equals(item.get("name")));
            if (!exists) {
                String city = extractCityHint(origin);
                if (city.isBlank() && overallCities.size() == 1) {
                    city = overallCities.get(0);
                }
                Map<String, Object> participant = new LinkedHashMap<>();
                participant.put("name", name);
                participant.put("origin", origin);
                if (!city.isBlank()) {
                    participant.put("city", city);
                }
                participants.add(participant);
            }
        }
        if (participants.size() < 2) {
            return Map.of();
        }
        Map<String, Object> multiOrigin = new LinkedHashMap<>();
        multiOrigin.put("mode", "meetup");
        multiOrigin.put("participants", participants);
        multiOrigin.put("meetupHint", extractMeetupHint(value));
        multiOrigin.put("summary", "多起点汇合：" + participants.stream()
                .map(item -> item.get("name") + "从" + item.get("origin"))
                .reduce((a, b) -> a + "，" + b)
                .orElse(""));
        return multiOrigin;
    }

    private String cleanOriginName(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("(先去|然后去|之后|再去).*$", "");
        value = value.replaceAll("(，|,|。|；|;).*$", "");
        return cleanPoiName(value);
    }

    private String extractMeetupHint(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("在([^，,。；;]{2,24})(汇合|碰面|集合)")
                .matcher(text == null ? "" : text);
        if (matcher.find()) {
            return cleanLocationCandidate(matcher.group(1));
        }
        return "";
    }

    private Map<String, Object> timeWindow(String text) {
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        java.util.regex.Matcher range = java.util.regex.Pattern
                .compile("([01]?\\d|2[0-3])\\s*[:.：]\\s*([0-5]\\d)\\s*[-—~到至]\\s*([01]?\\d|2[0-3])\\s*[:.：]\\s*([0-5]\\d)")
                .matcher(text == null ? "" : text);
        if (range.find()) {
            String start = String.format("%02d:%s", Integer.parseInt(range.group(1)), range.group(2));
            String end = String.format("%02d:%s", Integer.parseInt(range.group(3)), range.group(4));
            timeWindow.put("start", start);
            timeWindow.put("end", end);
            timeWindow.put("durationMinutes", minutesBetween(start, end));
            return timeWindow;
        }
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
        String explicitEnd = extractExplicitEnd(text, explicitStart);
        if (explicitEnd != null) {
            timeWindow.put("end", explicitEnd);
            if (explicitStart != null) {
                timeWindow.put("durationMinutes", minutesBetween(explicitStart, explicitEnd));
            }
        }
        return timeWindow;
    }

    private int minutesBetween(String start, String end) {
        try {
            java.time.LocalTime from = java.time.LocalTime.parse(start);
            java.time.LocalTime to = java.time.LocalTime.parse(end);
            int minutes = (int) java.time.Duration.between(from, to).toMinutes();
            return minutes > 0 ? minutes : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String extractExplicitStart(String text) {
        java.util.regex.Matcher digital = java.util.regex.Pattern
                .compile("(?<!\\d)([01]?\\d|2[0-3])\\s*[:.：]\\s*([0-5]\\d)(?!\\d)")
                .matcher(text);
        if (digital.find()) return String.format("%02d:%s", Integer.parseInt(digital.group(1)), digital.group(2));
        java.util.regex.Matcher explicitChineseHour = java.util.regex.Pattern
                .compile("(上午|早上|中午|下午|晚上|今晚)?\\s*([一二两三四五六七八九十]|1[0-2]|[1-9])\\s*点\\s*(半|[0-5]?\\d分?)?(开始|出发|左右|前后)?")
                .matcher(text);
        if (explicitChineseHour.find()) {
            int hour = parseHour(explicitChineseHour.group(2));
            String period = explicitChineseHour.group(1) == null ? "" : explicitChineseHour.group(1);
            if ((period.contains("下午") || period.contains("晚上") || period.contains("今晚")) && hour < 12) hour += 12;
            if (period.contains("中午") && hour < 11) hour += 12;
            String minuteText = explicitChineseHour.group(3) == null ? "" : explicitChineseHour.group(3);
            int minute = minuteText.contains("半") ? 30 : parseMinute(minuteText);
            return String.format("%02d:%02d", hour, minute);
        }
        java.util.regex.Matcher chinese = java.util.regex.Pattern
                .compile("(?<![点:\\d])([一二两三四五六七八九十])(?!\\s*点)")
                .matcher(text);
        if (!chinese.find()) return null;
        return null;
    }

    private Integer extractGroupTotal(String text, boolean family, boolean friends, boolean couple, boolean solo) {
        if (solo) return 1;
        if (couple) return 2;
        if (containsAny(text, "我和两个朋友", "我跟两个朋友")) return 3;
        if (containsAny(text, "带爸妈", "带父母", "我和爸妈", "我跟爸妈")) return 3;
        if (containsAny(text, "两个大人一个孩子", "2个大人1个孩子", "两大一小", "2大1小", "一家三口", "三口")) return 3;
        java.util.regex.Matcher compactFamily = java.util.regex.Pattern
                .compile("(\\d+)\\s*(大|个大人)\\s*(\\d+)\\s*(小|个孩子|个小孩|个儿童)")
                .matcher(text);
        if (compactFamily.find()) {
            return Integer.parseInt(compactFamily.group(1)) + Integer.parseInt(compactFamily.group(3));
        }
        java.util.regex.Matcher adultsChild = java.util.regex.Pattern
                .compile("([一二两三四五六七八九十\\d]+)个?大人([一二两三四五六七八九十\\d]+)个?(\\d{1,2}\\s*岁)?(孩子|小孩|儿童)")
                .matcher(text);
        if (adultsChild.find()) {
            return parseChineseNumber(adultsChild.group(1)) + parseChineseNumber(adultsChild.group(2));
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2})\\s*(个)?\\s*(人|朋友|同事|同学)").matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        java.util.regex.Matcher chinesePeople = java.util.regex.Pattern
                .compile("([一二两三四五六七八九十])\\s*(个)?\\s*(人|朋友|同事|同学)")
                .matcher(text);
        if (chinesePeople.find()) return parseChineseNumber(chinesePeople.group(1));
        if (family || friends) return null;
        return null;
    }

    private String groupComposition(String text, boolean family, boolean friends, boolean couple, boolean solo, Integer total) {
        if (solo) return "单人";
        if (couple) return "情侣/夫妻";
        if (containsAny(text, "两个人")) return "两个人";
        if (containsAny(text, "2大1小", "两大一小")) return "2大1小";
        if (friends && total != null) return "朋友同行";
        if (containsAny(text, "爸妈", "父母")) return "我和父母";
        if (family && total != null) return "家庭亲子";
        if (total != null) return total + "人同行";
        return null;
    }

    private String extractExplicitEnd(String text, String start) {
        String value = text == null ? "" : text;
        java.util.regex.Matcher digital = java.util.regex.Pattern
                .compile("(?<!\\d)([01]?\\d|2[0-3])\\s*[:.：]\\s*([0-5]\\d)(?!\\d)")
                .matcher(value);
        String first = null;
        while (digital.find()) {
            String time = String.format("%02d:%s", Integer.parseInt(digital.group(1)), digital.group(2));
            int from = Math.max(0, digital.start() - 8);
            int to = Math.min(value.length(), digital.end() + 12);
            String context = value.substring(from, to);
            if (containsAny(context, "到家", "回家", "结束", "前", "之前", "以前", "最晚", "要到")) {
                return time;
            }
            if (first == null) {
                first = time;
            } else if (start != null && start.equals(first)) {
                return time;
            }
        }
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
        String value = text == null ? "" : text;
        java.util.regex.Matcher amountWithUnit = java.util.regex.Pattern
                .compile("(\\d{2,5})\\s*(元|块|块钱)")
                .matcher(value);
        if (amountWithUnit.find()) return Integer.parseInt(amountWithUnit.group(1));
        java.util.regex.Matcher budgetPrefix = java.util.regex.Pattern
                .compile("(预算|总预算|人均|每人|花费|消费)[^0-9\\d]{0,8}(\\d{2,5})")
                .matcher(value);
        if (budgetPrefix.find()) return Integer.parseInt(budgetPrefix.group(2));
        return null;
    }

    private String extractVibe(String text, boolean family, boolean friends, boolean couple, boolean solo) {
        if (containsAny(text, "电影", "影院") && containsAny(text, "烤肉", "烧烤") && containsAny(text, "海边", "海滨", "沙滩")) {
            return "电影、烤肉和海边放松";
        }
        if (containsAny(text, "电影", "影院") && containsAny(text, "烤肉", "烧烤")) return "电影和烤肉";
        if (containsAny(text, "电影", "影院")) return "看电影";
        if (containsAny(text, "海边", "海滨", "沙滩")) return "海边放松";
        if (containsAny(text, "烤肉", "烧烤")) return "烤肉聚餐";
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
        String value = text == null ? "" : text;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2})\\s*(个)?\\s*小时").matcher(value);
        while (matcher.find()) {
            int from = Math.max(0, matcher.start() - 6);
            int to = Math.min(value.length(), matcher.end() + 4);
            String context = value.substring(from, to);
            if (containsAny(context, "排队", "等位", "等待")) {
                continue;
            }
            return Integer.parseInt(matcher.group(1)) * 60;
        }
        if (value.contains("半天")) return 4 * 60;
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

    private List<Map<String, Object>> extractExplicitPois(String text) {
        String value = text == null ? "" : text;
        List<Map<String, Object>> pois = new ArrayList<>();
        addExplicitPoi(pois, extractBetween(value, "先去", "看"), "activity", true);
        addExplicitPoi(pois, extractBetween(value, "然后去", "吃"), "dining", true);
        addExplicitPoi(pois, extractBetweenAny(value, "先去", List.of("，", ",", "然后去", "之后", "再去", "最后去", "18:", "16:", "结束")), "activity", true);
        addExplicitPoi(pois, extractBetweenAny(value, "然后去", List.of("，", ",", "之后", "再去", "最后去", "18:", "16:", "结束")), "dining", true);
        addExplicitPoi(pois, extractBetweenAny(value, "最后去", List.of("，", ",", "18:", "16:", "结束", "请给")), "extra", true);
        if (containsAny(value, "喝杯咖啡", "喝咖啡", "咖啡店")) {
            addExplicitPoi(pois, "咖啡店", "extra", false);
        }
        java.util.regex.Matcher named = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5A-Za-z0-9・·\\s()（）-]{2,36}(广场|展馆|博览广场|博物馆|艺术馆|公园|餐厅|海鲜|咖啡店|咖啡))")
                .matcher(value);
        while (named.find()) {
            String name = cleanPoiName(named.group(1));
            if (name.contains("星海广场") && !name.contains("店")) {
                continue;
            }
            String type = containsAny(name, "餐厅", "海鲜", "饭店") ? "dining"
                    : containsAny(name, "咖啡") ? "extra" : "activity";
            addExplicitPoi(pois, name, type, true);
        }
        return pois;
    }

    private List<Map<String, Object>> filterOriginOnlyPois(List<Map<String, Object>> pois,
                                                           Map<String, Object> multiOrigin,
                                                           String text) {
        if (pois.isEmpty() || multiOrigin.isEmpty()) {
            return pois;
        }
        List<String> origins = new ArrayList<>();
        Object participantsValue = multiOrigin.get("participants");
        if (participantsValue instanceof List<?> participants) {
            for (Object participant : participants) {
                if (participant instanceof Map<?, ?> map) {
                    Object originValue = map.get("origin");
                    String origin = originValue == null ? "" : String.valueOf(originValue).trim();
                    if (!origin.isBlank()) {
                        origins.add(origin);
                    }
                }
            }
        }
        if (origins.isEmpty()) {
            return pois;
        }
        String value = text == null ? "" : text;
        return pois.stream()
                .filter(poi -> {
                    String name = String.valueOf(poi.getOrDefault("name", "")).trim();
                    if (!origins.contains(name)) {
                        return true;
                    }
                    return containsAny(value, "先去" + name, "然后去" + name, "再去" + name, "之后去" + name);
                })
                .toList();
    }

    private List<Map<String, Object>> filterNonDestinationExplicitPois(List<Map<String, Object>> pois, String text) {
        if (pois.isEmpty()) {
            return pois;
        }
        return pois.stream()
                .filter(poi -> isDestinationExplicitPoi(String.valueOf(poi.getOrDefault("name", "")), text))
                .toList();
    }

    private boolean isDestinationExplicitPoi(String name, String text) {
        String value = text == null ? "" : text;
        String poiName = name == null ? "" : name.trim();
        if (poiName.isBlank()) {
            return false;
        }
        if (isGenericDemandPhrase(poiName)) {
            return false;
        }
        if (hasDestinationVerb(poiName, value)) {
            return true;
        }
        if (isLocationAnchorOnly(poiName, value)) {
            return false;
        }
        return true;
    }

    private boolean hasDestinationVerb(String name, String text) {
        return containsAny(text,
                "先去" + name, "然后去" + name, "之后去" + name, "再去" + name, "最后去" + name,
                "逛" + name, "去逛" + name, "看" + name, "去看" + name, "吃" + name, "去吃" + name);
    }

    private boolean isLocationAnchorOnly(String name, String text) {
        return containsAny(text,
                "在" + name + "附近", "在" + name + "汇合", "在" + name + "碰面",
                "在" + name + "出发", "在" + name + "集合", "在" + name + "，", "在" + name + ",");
    }

    private boolean isGenericDemandPhrase(String name) {
        String value = name == null ? "" : name.trim();
        if (List.of("室内活动", "亲子活动", "晚餐", "午餐", "吃饭和咖啡", "吃饭看电影").contains(value)) {
            return true;
        }
        return containsAny(value,
                "想优先", "担心下雨", "安排亲子活动", "和咖啡", "一起吃饭", "想一起吃饭",
                "预算", "时间", "路线", "方案", "同行", "朋友在");
    }

    private String extractBetween(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        if (start < 0) return "";
        int contentStart = start + startToken.length();
        int end = text.indexOf(endToken, contentStart);
        if (end < 0 || end <= contentStart) return "";
        return text.substring(contentStart, end);
    }

    private String extractBetweenAny(String text, String startToken, List<String> endTokens) {
        int start = text.indexOf(startToken);
        if (start < 0) return "";
        int contentStart = start + startToken.length();
        int end = text.length();
        for (String token : endTokens) {
            int index = text.indexOf(token, contentStart);
            if (index >= contentStart && index < end) {
                end = index;
            }
        }
        if (end <= contentStart) return "";
        return text.substring(contentStart, end);
    }

    private void addExplicitPoi(List<Map<String, Object>> pois, String rawName, String type, boolean locked) {
        String name = cleanPoiName(rawName);
        if (name.isBlank() || name.length() < 2) return;
        boolean exists = pois.stream().anyMatch(item -> sameExplicitPoi(name, String.valueOf(item.get("name"))));
        if (exists) return;
        Map<String, Object> poi = new LinkedHashMap<>();
        poi.put("name", name);
        poi.put("type", type);
        poi.put("locked", locked);
        pois.add(poi);
    }

    private String cleanPoiName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        name = name.replaceAll("^[，,。\\s]+|[，,。\\s]+$", "");
        name = name.replaceFirst("^\\d{1,4}", "");
        if (name.contains("咖啡") && containsAny(name, "喝杯", "喝咖啡", "咖啡店")) {
            return "咖啡店";
        }
        name = name.replaceAll("^(我和[^在，,]{1,16}在|我跟[^在，,]{1,16}在|我在|我现在在|在|朋友[A-Za-z0-9一二三四五六七八九十]?在|好友[A-Za-z0-9一二三四五六七八九十]?在|同事[A-Za-z0-9一二三四五六七八九十]?在)", "");
        name = name.replaceAll("^(先去|然后去|之后去|之后|再去|想先|想去|逛一下|吃一顿|买杯|喝杯)", "");
        name = name.replace("要到家", "").replace("帮我规划顺路的路线", "");
        return name.trim();
    }

    private boolean sameExplicitPoi(String left, String right) {
        String a = normalizeExplicitPoiName(left);
        String b = normalizeExplicitPoiName(right);
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private String normalizeExplicitPoiName(String name) {
        return name == null ? "" : name
                .replaceAll("[\\s()（）・·\\-]", "")
                .replace("满员", "")
                .replace("无票", "")
                .replace("售罄", "")
                .replace("排队", "");
    }

    private List<String> explicitPoiNames(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object rawName = map.get("name");
                String name = rawName == null ? "" : String.valueOf(rawName).trim();
                if (!name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private Map<String, Object> extractExecutionRequests(String text) {
        String value = text == null ? "" : text;
        Map<String, Object> requests = new LinkedHashMap<>();
        requests.put("ticket", containsAny(value, "订票", "门票", "买票", "购票"));
        requests.put("restaurant", containsAny(value, "订座", "订餐", "座位", "预约餐厅"));
        requests.put("rideHailing", containsAny(value, "叫车", "打车", "提前叫", "回家的车"));
        return requests;
    }

    private String extractLocationHint(String text, String city) {
        if (text == null || text.isBlank()) return null;
        if (containsAny(text, "我附近", "当前位置") || "附近".equals(text.trim())) return null;
        List<String> preferredAnchors = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,24}(广场|公园|大学|商场|中心|车站|火车站|地铁站|机场|景区|街|路|区|县|镇|商圈|湖|馆|门|寺))")
                .matcher(text);
        while (matcher.find()) {
            String candidate = cleanLocationCandidate(matcher.group(1));
            int start = Math.max(0, matcher.start() - 8);
            int end = Math.min(text.length(), matcher.end() + 4);
            String context = text.substring(start, end);
            if (containsAny(context, "我现在在", "我在", "附近", "碰面", "出发", "天安门")) {
                preferredAnchors.add(candidate);
            }
            if (!looksLikeConstraintPhrase(candidate)) {
                preferredAnchors.add(candidate);
            }
        }
        if (!preferredAnchors.isEmpty()) return preferredAnchors.get(0);
        return city == null || city.isBlank() ? null : city;
    }

    private boolean looksLikeConstraintPhrase(String candidate) {
        return containsAny(candidate, "想走", "排队", "预算", "时间", "小时", "分钟", "顺路", "绕路");
    }

    private String cleanLocationCandidate(String candidate) {
        String value = candidate == null ? "" : candidate;
        value = value.replaceFirst("^(我现在在|我想去|我周末去|我在|想去|去|在)", "");
        int atIndex = value.lastIndexOf("在");
        if (atIndex > 0 && atIndex < value.length() - 1) {
            value = value.substring(atIndex + 1);
        }
        int goIndex = value.lastIndexOf("去");
        if (goIndex > 0 && goIndex < value.length() - 1) {
            value = value.substring(goIndex + 1);
        }
        return value;
    }

    private String extractCityHint(String text) {
        return extractCityMentions(text).stream().findFirst().orElse("");
    }

    private boolean containsCitySignal(String message, String city) {
        if (message == null || city == null || city.isBlank()) {
            return false;
        }
        int index = message.indexOf(city);
        while (index >= 0) {
            int end = index + city.length();
            String suffix = end < message.length()
                    ? message.substring(end, Math.min(message.length(), end + 2))
                    : "";
            if (!startsWithAny(suffix, "东路", "西路", "南路", "北路", "路", "街", "大道", "地铁", "站")) {
                return true;
            }
            index = message.indexOf(city, end);
        }
        return false;
    }

    private boolean startsWithAny(String value, String... prefixes) {
        String text = value == null ? "" : value;
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> knownCities() {
        return List.of(
                "北京", "上海", "天津", "重庆", "广州", "深圳", "杭州", "南京", "苏州", "成都", "武汉", "西安",
                "长沙", "郑州", "青岛", "济南", "厦门", "福州", "宁波", "无锡", "合肥", "昆明", "南昌", "南宁",
                "贵阳", "太原", "石家庄", "沈阳", "长春", "哈尔滨", "大连", "珠海", "佛山", "东莞", "泉州",
                "洛阳", "海口", "三亚", "乌鲁木齐", "兰州", "银川", "西宁", "拉萨", "呼和浩特");
    }

    private boolean isExplicitCityMention(String text, int index, String city) {
        int end = index + city.length();
        if (!hasCityLeadingBoundary(text, index)) {
            return false;
        }
        if (end < text.length() && text.charAt(end) == '市') {
            return true;
        }
        return !hasCitySuffixExclusion(text, end);
    }

    private boolean hasCityLeadingBoundary(String text, int index) {
        if (index <= 0) {
            return true;
        }
        char previous = text.charAt(index - 1);
        if (Character.isWhitespace(previous) || isBoundaryPunctuation(previous)) {
            return true;
        }
        return "在去回到来从往离住于向至跟和与同陪约赴经返".indexOf(previous) >= 0;
    }

    private boolean hasCitySuffixExclusion(String text, int start) {
        if (start >= text.length()) {
            return false;
        }
        for (String suffix : List.of("东路", "西路", "南路", "北路", "路", "街", "巷", "大道", "胡同", "弄", "号", "店",
                "餐厅", "饭店", "酒店", "宾馆", "公馆", "菜馆", "小吃", "烤鸭", "生煎", "火锅", "酸奶")) {
            if (text.startsWith(suffix, start)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBoundaryPunctuation(char ch) {
        return ",，。；;、()（）:：- ".indexOf(ch) >= 0;
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

    private int parseChineseNumber(String text) {
        String value = text == null ? "" : text.trim();
        if (value.matches("\\d+")) {
            return Integer.parseInt(value);
        }
        return switch (value) {
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
            default -> 0;
        };
    }

    private boolean isPlaceholderNearbyLocation(String text) {
        return "附近".equals(text)
                || "我附近".equals(text)
                || "在我附近".equals(text)
                || "当前位置".equals(text)
                || "当前地点".equals(text)
                || "本地".equals(text)
                || "我所在城市".equals(text);
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

    private record CityReference(String name, int index) {}
}
