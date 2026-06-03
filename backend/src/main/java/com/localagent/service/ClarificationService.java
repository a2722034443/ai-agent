package com.localagent.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.localagent.config.ExternalClientProperties;
import org.springframework.stereotype.Component;

@Component
public class ClarificationService {
    private static final List<String> REQUIRED_KEYS = List.of(
            "location", "timeWindow", "duration", "group", "budget", "preferences"
    );

    private final ClarificationAgent clarificationAgent;
    private final ExternalClientProperties properties;

    public ClarificationService(ClarificationAgent clarificationAgent, ExternalClientProperties properties) {
        this.clarificationAgent = clarificationAgent;
        this.properties = properties;
    }

    public Map<String, Object> buildClarification(UUID planId, Map<String, Object> intent, String rawMessage) {
        List<Map<String, Object>> fields = missingFields(intent, rawMessage);
        intent.put("missingFields", fields.stream().map(field -> field.get("key")).toList());
        // 无论是否需要澄清，都先应用默认值
        applyDefaults(intent);
        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("message", "还需要补齐几个关键信息，补齐后我再查询真实地点并生成方案。");
        fallback.put("fields", fields);
        fallback.put("missingFields", fields.stream().map(field -> field.get("key")).toList());
        return clarificationAgent.clarify(planId, rawMessage == null ? "" : rawMessage, intent, fallback);
    }

    public Map<String, Object> buildClarification(Map<String, Object> intent) {
        return buildClarification(null, intent, "");
    }

    public Map<String, Object> mergeAnswers(Map<String, Object> intent, Map<String, Object> answers) {
        Map<String, Object> merged = new LinkedHashMap<>(intent == null ? Map.of() : intent);
        Map<String, String> normalizedAnswers = normalizeAnswers(answers);
        ensureUserFacts(merged, string(merged.get("rawMessage")));
        if (normalizedAnswers.isEmpty()) {
            mirrorDerivedFields(merged);
            return merged;
        }

        Map<String, Object> userFacts = mutableMap(merged.get("userFacts"));
        Map<String, Object> existingAnswers = mutableMap(userFacts.get("answers"));
        normalizedAnswers.forEach(existingAnswers::put);
        userFacts.put("answers", existingAnswers);
        merged.put("userFacts", userFacts);

        Map<String, Object> location = mutableMap(merged.get("location"));
        Map<String, Object> group = mutableMap(merged.get("group"));
        Map<String, Object> timeWindow = mutableMap(merged.get("time_window"));
        Map<String, Object> preferences = mutableMap(merged.get("soft_preferences"));

        putLocation(location, normalizedAnswers.get("location"));
        putTime(timeWindow, normalizedAnswers.get("timeWindow"));
        putDuration(timeWindow, normalizedAnswers.get("duration"));
        putGroup(group, normalizedAnswers.get("group"));
        putBudget(preferences, normalizedAnswers.get("budget"));
        putPreferences(merged, preferences, normalizedAnswers.get("preferences"));
        putOptionalButlerFacts(merged, normalizedAnswers);
        mergeStructuredPatch(merged, preferences, answers);

        merged.put("location", location);
        merged.put("group", group);
        merged.put("time_window", timeWindow);
        merged.put("soft_preferences", preferences);
        mirrorDerivedFields(merged);
        return merged;
    }

    public Map<String, Object> ensureUserFacts(Map<String, Object> intent, String rawMessage) {
        Map<String, Object> userFacts = mutableMap(intent.get("userFacts"));
        if (!string(rawMessage).isBlank()) {
            userFacts.put("rawMessage", rawMessage);
        } else {
            userFacts.putIfAbsent("rawMessage", string(intent.get("rawMessage")));
        }
        userFacts.putIfAbsent("answers", new LinkedHashMap<String, Object>());
        intent.put("userFacts", userFacts);
        mirrorDerivedFields(intent);
        return intent;
    }

    private List<Map<String, Object>> missingFields(Map<String, Object> intent, String rawMessage) {
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> location = mutableMap(intent.get("location"));
        Map<String, Object> timeWindow = mutableMap(intent.get("time_window"));
        Map<String, Object> group = mutableMap(intent.get("group"));
        Map<String, Object> preferences = mutableMap(intent.get("soft_preferences"));
        String message = string(rawMessage);
        applyDefaultOriginForCurrentLocation(location, message);
        intent.put("location", location);

        if (!hasActionableLocation(location)) {
            fields.add(field("location", "\u5730\u70b9", locationQuestion(location),
                    locationSuggestions(message)));
        }
        if (blank(timeWindow.get("start")) || hasInvalidTime(timeWindow)) {
            fields.add(field("timeWindow", "\u5f00\u59cb\u65f6\u95f4", timeQuestion(timeWindow),
                    timeSuggestions(message)));
        }
        if (blank(timeWindow.get("durationMinutes")) && blank(timeWindow.get("end"))) {
            fields.add(field("duration", "\u6e38\u73a9\u65f6\u957f", durationQuestion(timeWindow),
                    durationSuggestions(message)));
        }
        if (blank(group.get("composition")) || blank(group.get("total"))) {
            fields.add(field("group", "\u540c\u884c\u4eba", "\u51e0\u4e2a\u4eba\u540c\u884c\uff1f\u6709\u6ca1\u6709\u5b69\u5b50\u3001\u8001\u4eba\u6216\u9700\u8981\u7167\u987e\u7684\u4eba\uff1f",
                    groupSuggestions(message)));
        }
        if (blank(preferences.get("budgetAmount")) && blank(preferences.get("budget"))) {
            fields.add(field("budget", "\u9884\u7b97", "\u603b\u9884\u7b97\u5927\u6982\u662f\u591a\u5c11\uff1f",
                    budgetSuggestions(group)));
        }
        if (blank(preferences.get("vibe")) && blank(intent.get("scenario"))) {
            fields.add(field("preferences", "\u6838\u5fc3\u9700\u6c42", "\u8fd9\u6b21\u6700\u60f3\u6ee1\u8db3\u4ec0\u4e48\u9700\u6c42\uff1f",
                    preferenceSuggestions(message, group)));
        }
        return fields;
    }

    private void applyDefaults(Map<String, Object> intent) {
        Map<String, Object> timeWindow = mutableMap(intent.get("time_window"));
        Map<String, Object> group = mutableMap(intent.get("group"));
        Map<String, Object> preferences = mutableMap(intent.get("soft_preferences"));

        // duration 默认 180 分钟
        if (blank(timeWindow.get("durationMinutes")) && blank(timeWindow.get("end"))) {
            timeWindow.put("durationMinutes", 180);
            intent.put("time_window", timeWindow);
        }
        // group 默认 2 人 friends
        if (blank(group.get("total"))) {
            group.put("total", 2);
            group.putIfAbsent("composition", "两人");
            intent.put("group", group);
        }
        // budget 默认 500 元
        if (blank(preferences.get("budgetAmount")) && blank(preferences.get("budget"))) {
            preferences.put("budgetAmount", 500);
            preferences.put("budget", "medium");
            intent.put("soft_preferences", preferences);
        }
        // vibe 默认 general
        if (blank(preferences.get("vibe"))) {
            preferences.put("vibe", "轻松出行");
            intent.put("soft_preferences", preferences);
        }
    }

    private Map<String, Object> field(String key, String label, String question, List<String> suggestions) {
        List<String> clipped = suggestions.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(3)
                .toList();
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", key);
        field.put("label", label);
        field.put("question", question);
        field.put("type", "text");
        field.put("suggestions", clipped);
        field.put("options", options(clipped));
        field.put("allowCustom", true);
        field.put("reason", reason(key));
        field.put("expectedAnswerHint", expectedAnswerHint(key));
        return field;
    }

    private List<Map<String, Object>> options(List<String> suggestions) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < suggestions.size(); i++) {
            options.add(Map.of("code", String.valueOf((char) ('A' + i)), "text", suggestions.get(i)));
        }
        return options;
    }

    private String reason(String key) {
        return switch (key) {
            case "location" -> "真实地点搜索需要可以定位的城市、商圈、地标、地址或坐标。";
            case "timeWindow" -> "开始时间会影响营业状态、用餐时段、交通和路线安排。";
            case "duration" -> "没有时长或结束时间，无法判断行程是否可执行。";
            case "group" -> "同行人构成会影响儿童友好、低步行、座位和安全判断。";
            case "budget" -> "预算会影响餐厅、人均价格和方案密度。";
            case "preferences" -> "核心需求决定搜索词、POI 类型和排序权重。";
            default -> "补齐后才能生成真实可执行方案。";
        };
    }

    private String expectedAnswerHint(String key) {
        return switch (key) {
            case "location" -> "例如：杭州西湖附近；或：当前位置 120.123456,30.123456";
            case "timeWindow" -> "例如：10:00、14:30、晚上7点";
            case "duration" -> "例如：3小时左右、晚饭后结束、21:30前结束";
            case "group" -> "例如：两个大人一个6岁孩子、情侣两人、4个朋友";
            case "budget" -> "例如：总预算600元、每人200左右、500-800元";
            case "preferences" -> "例如：亲子室内活动和清淡晚餐、少走路、不要排队太久";
            default -> "用自然语言填写即可。";
        };
    }

    private Map<String, String> normalizeAnswers(Map<String, Object> answers) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (answers == null || answers.isEmpty()) {
            return normalized;
        }
        answers.forEach((key, value) -> {
            if (key != null && REQUIRED_KEYS.contains(key) && value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    normalized.put(key, text);
                }
            }
        });
        return normalized;
    }

    private void putLocation(Map<String, Object> location, String text) {
        if (text == null || text.isBlank()) return;
        double[] coordinates = parseCoordinates(text);
        if (coordinates == null && isCurrentLocationText(text)) {
            coordinates = parseCoordinates(properties.getAmap().getDefaultOrigin());
        }
        if (coordinates != null) {
            location.put("city", inferCity(text));
            location.put("district", text.contains("当前位置") ? "当前位置" : text);
            location.put("lng", coordinates[0]);
            location.put("lat", coordinates[1]);
            location.put("radius", "nearby");
            location.remove("needsConcreteAnchor");
            return;
        }
        if (!isConcreteLocation(text)) {
            location.remove("city");
            location.put("district", text);
            location.put("radius", "nearby");
            location.put("needsConcreteAnchor", true);
            return;
        }
        location.put("city", inferCity(text));
        location.put("district", text);
        location.put("radius", "nearby");
        location.remove("needsConcreteAnchor");
    }

    private void applyDefaultOriginForCurrentLocation(Map<String, Object> location, String text) {
        if (hasCoordinates(location) || !isCurrentLocationText(text)) return;
        double[] coordinates = parseCoordinates(properties.getAmap().getDefaultOrigin());
        if (coordinates == null) return;
        location.put("city", inferCity(text));
        location.put("district", "当前位置");
        location.put("lng", coordinates[0]);
        location.put("lat", coordinates[1]);
        location.put("radius", "nearby");
        location.remove("needsConcreteAnchor");
    }

    private void putTime(Map<String, Object> timeWindow, String text) {
        if (text == null || text.isBlank()) return;
        timeWindow.put("period", text);
        timeWindow.remove("invalidReason");
        if (isBroadOrInvalidTime(text)) {
            timeWindow.remove("start");
            timeWindow.put("invalidReason", "时间表达还不够明确");
            return;
        }
        String explicitStart = extractExplicitStart(text);
        if (explicitStart != null) {
            timeWindow.put("start", explicitStart);
        } else {
            timeWindow.put("invalidReason", "时间表达还不够明确");
        }
    }

    private void putDuration(Map<String, Object> timeWindow, String text) {
        if (text == null || text.isBlank()) return;
        Integer minutes = extractDurationMinutes(text);
        if (minutes != null) {
            timeWindow.put("durationMinutes", minutes);
        } else {
            timeWindow.put("durationText", text);
        }
        if (containsAny(text, "结束", "之前", "以前", "前")) {
            timeWindow.put("end", text);
        }
    }

    private void putGroup(Map<String, Object> group, String text) {
        if (text == null || text.isBlank()) return;
        if (isNoCompanionText(text)) {
            group.put("composition", "单人");
            group.put("total", 1);
            return;
        }
        group.put("composition", text);
        Integer total = extractGroupTotal(text);
        if (total != null) {
            group.put("total", total);
        }
        if (containsAny(text, "孩子", "小孩", "儿童", "亲子", "宝宝")) {
            group.put("hasChildren", true);
            group.putIfAbsent("childAge", 5);
        }
        if (containsAny(text, "老人", "长辈", "父母", "行动不便")) {
            group.put("hasElderly", true);
        }
    }

    private void putBudget(Map<String, Object> preferences, String text) {
        if (text == null || text.isBlank()) return;
        Integer amount = extractNumber(text);
        if (amount != null) {
            preferences.put("budgetAmount", amount);
            preferences.put("budget", amount < 500 ? "low" : amount > 1000 ? "high" : "medium");
        } else {
            preferences.put("budget", text);
        }
    }

    private void putPreferences(Map<String, Object> intent, Map<String, Object> preferences, String text) {
        if (text == null || text.isBlank()) return;
        preferences.put("vibe", text);
        if (containsAny(text, "亲子", "孩子", "儿童")) intent.put("scenario", "family");
        else if (containsAny(text, "朋友", "聚会", "团建")) intent.put("scenario", "friends");
        else if (containsAny(text, "情侣", "约会", "夫妻")) intent.put("scenario", "couple");
        else if (containsAny(text, "一个人", "独自", "自己")) intent.put("scenario", "solo");
        else intent.putIfAbsent("scenario", "general");
    }

    private void putOptionalButlerFacts(Map<String, Object> intent, Map<String, String> answers) {
        List<String> hard = new ArrayList<>(stringList(intent.get("hard_constraints")));
        Map<String, Object> preferences = mutableMap(intent.get("soft_preferences"));
        String joined = String.join("。", answers.values());
        addHardIf(joined, hard, "儿童友好", "孩子", "小孩", "儿童", "亲子", "宝宝");
        addHardIf(joined, hard, "老人友好", "老人", "长辈", "行动不便", "少走路", "无障碍");
        addHardIf(joined, hard, "宠物友好", "宠物", "狗", "猫");
        addHardIf(joined, hard, "低步行", "少走路", "不要太远", "地铁", "停车");
        addHardIf(joined, hard, "饮食限制", "忌口", "过敏", "清淡", "低卡", "素食", "不吃辣");
        if (containsAny(joined, "室内", "下雨", "太热", "太冷")) {
            preferences.put("indoorOutdoor", "indoor_preferred");
        }
        if (containsAny(joined, "不要排队", "排队少", "快一点")) {
            preferences.put("queueTolerance", "low");
        }
        intent.put("hard_constraints", hard);
        intent.put("soft_preferences", preferences);
    }

    @SuppressWarnings("unchecked")
    private void mergeStructuredPatch(Map<String, Object> intent, Map<String, Object> preferences, Map<String, Object> answers) {
        if (answers == null) return;
        Object preferencePatch = answers.get("soft_preferences");
        if (preferencePatch instanceof Map<?, ?> patch) {
            patch.forEach((key, value) -> {
                if (key != null && value != null) {
                    preferences.put(String.valueOf(key), value);
                }
            });
        }
        mergeList(intent, answers, "hard_constraints");
        mergeList(intent, answers, "excludedPois");
        copyIfPresent(intent, answers, "requestedPlanCount");
        copyIfPresent(intent, answers, "requestedStopCount");
        copyIfPresent(intent, answers, "stopCountPreference");
        copyIfPresent(intent, answers, "scenario");
    }

    private void mirrorDerivedFields(Map<String, Object> intent) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("location", mutableMap(intent.get("location")));
        derived.put("timeWindow", mutableMap(intent.get("time_window")));
        derived.put("group", mutableMap(intent.get("group")));
        derived.put("budget", mutableMap(intent.get("soft_preferences")).get("budget"));
        derived.put("preferences", mutableMap(intent.get("soft_preferences")));
        intent.put("derived", derived);
    }

    private boolean hasActionableLocation(Map<String, Object> location) {
        if (hasCoordinates(location)) return true;
        String city = string(location.get("city"));
        String district = string(location.get("district"));
        if (city.isBlank() && district.isBlank()) return false;
        if (Boolean.TRUE.equals(location.get("needsConcreteAnchor"))) return false;
        return isConcreteLocation(city + district);
    }

    private boolean hasCoordinates(Map<String, Object> location) {
        return location.get("lng") instanceof Number && location.get("lat") instanceof Number;
    }

    private boolean hasInvalidTime(Map<String, Object> timeWindow) {
        return !blank(timeWindow.get("invalidReason"));
    }

    private String locationQuestion(Map<String, Object> location) {
        String district = string(location.get("district"));
        if (!district.isBlank() && !isConcreteLocation(district)) {
            return "“" + district + "”还不能定位，请填写城市加具体商圈、地标、地址，或授权当前位置。";
        }
        return "你想在哪个城市、商圈、地标或具体地址附近安排？";
    }

    private String timeQuestion(Map<String, Object> timeWindow) {
        if (!blank(timeWindow.get("invalidReason"))) {
            return "开始时间还不够明确，请换成具体时间，例如 10:00、14:30 或 晚上7点。";
        }
        return "具体几点开始？";
    }

    private String durationQuestion(Map<String, Object> timeWindow) {
        String start = string(timeWindow.get("start"));
        if (!start.isBlank()) {
            return "已识别开始时间 " + start + "，还需要知道大概玩多久，或者最晚几点结束。";
        }
        return "大概想玩多久，或者最晚几点结束？";
    }

    private List<String> locationSuggestions(String message) {
        String city = extractCityHint(message);
        if (city.isBlank()) return List.of();
        return List.of(city + "核心商圈附近", city + "某个地铁站/商场/公园附近", city + "你常去的地标附近");
    }

    private List<String> timeSuggestions(String message) {
        if (containsAny(message, "早上", "上午")) return List.of("09:30", "10:00", "10:30");
        if (containsAny(message, "晚上", "晚饭", "今晚")) return List.of("18:00", "18:30", "19:00");
        if (containsAny(message, "下午")) return List.of("14:00", "15:00", "16:00");
        return List.of("10:00", "14:00", "19:00");
    }

    private List<String> durationSuggestions(String message) {
        if (containsAny(message, "晚饭", "晚上")) return List.of("2小时左右", "3小时左右", "晚饭后结束");
        return List.of("3小时左右", "4小时左右", "半天");
    }

    private List<String> groupSuggestions(String message) {
        if (containsAny(message, "情侣", "约会", "对象")) return List.of("情侣两人", "夫妻两人", "两个人");
        if (containsAny(message, "朋友", "聚会")) return List.of("2个朋友", "4个朋友", "6个朋友");
        if (containsAny(message, "孩子", "亲子", "儿童")) return List.of("两个大人一个孩子", "一个大人一个孩子", "一家三口");
        return List.of("我自己", "情侣两人", "两个大人一个孩子");
    }

    private List<String> budgetSuggestions(Map<String, Object> group) {
        int total = group.get("total") instanceof Number number ? number.intValue() : 2;
        if (total <= 2) return List.of("300元", "600元", "1000元");
        if (total <= 4) return List.of("600元", "1000元", "1500元");
        return List.of("1000元", "1500元", "2000元");
    }

    private List<String> preferenceSuggestions(String message, Map<String, Object> group) {
        String composition = string(group.get("composition"));
        if (containsAny(message + composition, "情侣", "约会", "夫妻")) {
            return List.of("展览和晚餐", "咖啡散步和轻食", "安静约会和夜景");
        }
        if (containsAny(message, "朋友", "聚会")) {
            return List.of("朋友聚会和吃饭", "密室/桌游加晚餐", "轻松逛逛和烧烤");
        }
        if (containsAny(message + composition, "孩子", "亲子", "儿童")) {
            return List.of("亲子活动和晚餐", "儿童友好室内活动", "博物馆和清淡晚餐");
        }
        return List.of("轻松逛逛和吃饭", "文化展览和咖啡", "室内活动和简餐");
    }

    private boolean isConcreteLocation(String text) {
        String value = string(text);
        if (value.isBlank() || isPlaceholderLocation(value)) return false;
        if (parseCoordinates(value) != null) return true;
        if (List.of("附近", "我附近", "在我附近", "当前地点", "当前位置", "本地").contains(value)) return false;
        if (!hasCityHint(value)) return false;
        return value.length() >= 2;
    }

    private boolean isPlaceholderLocation(String value) {
        String text = string(value);
        return text.contains("+")
                || text.contains("我所在城市")
                || text.contains("具体商圈")
                || text.contains("地铁站/地标")
                || text.contains("具体地址")
                || text.contains("附近道路")
                || text.equals("附近")
                || text.equals("我附近");
    }

    private boolean isBroadOrInvalidTime(String text) {
        String value = string(text);
        return List.of("今天", "明天", "上午", "下午", "晚上", "今晚", "周末", "下班后", "饭点").contains(value)
                || value.contains("早上12点")
                || value.contains("上午12点")
                || value.contains("凌晨12点");
    }

    private String extractExplicitStart(String text) {
        if (containsAny(text, "现在", "立刻", "马上", "立即")) {
            LocalTime now = LocalTime.now().withSecond(0).withNano(0);
            return String.format("%02d:%02d", now.getHour(), now.getMinute());
        }
        java.util.regex.Matcher digital = java.util.regex.Pattern
                .compile("(?<!\\d)([01]?\\d|2[0-3])\\s*[:.：]\\s*([0-5]\\d)(?!\\d)")
                .matcher(text);
        if (digital.find()) {
            return String.format("%02d:%s", Integer.parseInt(digital.group(1)), digital.group(2));
        }
        java.util.regex.Matcher hourOnly = java.util.regex.Pattern
                .compile("^\\s*([01]?\\d|2[0-3])\\s*(点|时)?\\s*$")
                .matcher(text);
        if (hourOnly.find()) {
            return String.format("%02d:00", Integer.parseInt(hourOnly.group(1)));
        }
        java.util.regex.Matcher chinese = java.util.regex.Pattern
                .compile("(上午|早上|中午|下午|晚上|今晚)?\\s*([一二两三四五六七八九十]|1[0-2]|[1-9])\\s*点\\s*(半|[0-5]?\\d分?)?")
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

    private Integer extractDurationMinutes(String text) {
        java.util.regex.Matcher range = java.util.regex.Pattern.compile("(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*小时").matcher(text);
        if (range.find()) return Integer.parseInt(range.group(2)) * 60;
        java.util.regex.Matcher hour = java.util.regex.Pattern.compile("(\\d{1,2})\\s*(个)?\\s*小时").matcher(text);
        if (hour.find()) return Integer.parseInt(hour.group(1)) * 60;
        java.util.regex.Matcher minute = java.util.regex.Pattern.compile("(\\d{2,3})\\s*分钟").matcher(text);
        if (minute.find()) return Integer.parseInt(minute.group(1));
        if (text.contains("半天")) return 4 * 60;
        return null;
    }

    private Integer extractGroupTotal(String text) {
        if (isNoCompanionText(text) || containsAny(text, "我自己", "一个人", "1人", "单人", "独自", "自己")) return 1;
        if (containsAny(text, "情侣", "两人", "两个人", "2人", "夫妻")) return 2;
        if (containsAny(text, "两个大人一个孩子", "一家三口", "三口", "两大一小")) return 3;
        Integer number = extractNumber(text);
        return number == null || number > 50 ? null : number;
    }

    private boolean isCurrentLocationText(String text) {
        String value = string(text);
        return List.of("附近", "我附近", "在我附近", "当前地点", "当前位置", "本地").contains(value)
                || value.contains("当前位置")
                || value.contains("我附近");
    }

    private boolean isNoCompanionText(String text) {
        String value = string(text);
        return List.of("无", "没有", "没", "0", "零").contains(value)
                || containsAny(value, "没有同行人", "没人同行", "没有人同行", "无同行人", "就我", "只有我", "自己去");
    }

    private Integer extractNumber(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,5})").matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        return Integer.parseInt(matcher.group(1));
    }

    private double[] parseCoordinates(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(-?\\d{2,3}\\.\\d{3,})\\s*[,，\\s]\\s*(-?\\d{1,2}\\.\\d{3,})")
                .matcher(string(text));
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

    private String inferCity(String text) {
        String city = extractCityHint(text);
        return city.isBlank() ? "" : city;
    }

    private boolean hasCityHint(String text) {
        return !extractCityHint(text).isBlank();
    }

    private String extractCityHint(String text) {
        String value = string(text);
        if (value.isBlank()) return "";
        java.util.regex.Matcher explicit = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5]{2,12}?)(市|区|县)")
                .matcher(value);
        if (explicit.find() && explicit.group(0).endsWith("市")) {
            return explicit.group(1);
        }
        for (String city : List.of(
                "北京", "上海", "天津", "重庆", "广州", "深圳", "杭州", "南京", "苏州", "成都", "武汉", "西安",
                "长沙", "郑州", "青岛", "济南", "厦门", "福州", "宁波", "无锡", "合肥", "昆明", "南昌", "南宁",
                "贵阳", "太原", "石家庄", "沈阳", "长春", "哈尔滨", "大连", "珠海", "佛山", "东莞", "泉州",
                "洛阳", "海口", "三亚", "乌鲁木齐", "兰州", "银川", "西宁", "拉萨", "呼和浩特")) {
            if (value.contains(city)) return city;
        }
        return "";
    }

    private void addHardIf(String text, List<String> hard, String label, String... keywords) {
        if (containsAny(text, keywords) && !hard.contains(label)) hard.add(label);
    }

    private void mergeList(Map<String, Object> intent, Map<String, Object> answers, String key) {
        Object value = answers.get(key);
        if (!(value instanceof List<?> patchItems)) return;
        List<String> merged = new ArrayList<>(stringList(intent.get(key)));
        for (Object item : patchItems) {
            String text = String.valueOf(item);
            if (!text.isBlank() && !merged.contains(text)) merged.add(text);
        }
        intent.put(key, merged);
    }

    private void copyIfPresent(Map<String, Object> intent, Map<String, Object> answers, String key) {
        Object value = answers.get(key);
        if (value != null && !String.valueOf(value).isBlank()) intent.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        return value instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value));
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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
}
