package com.localagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClarificationService {
    private final ClarificationAgent clarificationAgent;

    public ClarificationService(ClarificationAgent clarificationAgent) {
        this.clarificationAgent = clarificationAgent;
    }

    public Map<String, Object> buildClarification(UUID planId, Map<String, Object> intent, String rawMessage) {
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> location = castMap(intent.get("location"));
        Map<String, Object> group = castMap(intent.get("group"));
        Map<String, Object> timeWindow = castMap(intent.get("time_window"));
        Map<String, Object> preferences = castMap(intent.get("soft_preferences"));
        String message = string(rawMessage);

        if (!hasActionableLocation(location)) {
            fields.add(field("location", "地点", locationQuestion(location), "text",
                    locationSuggestions(message, group)));
        }
        if (blank(timeWindow.get("start")) || hasInvalidTime(timeWindow)) {
            fields.add(field("timeWindow", "开始时间", timeQuestion(timeWindow), "choice",
                    timeSuggestions(message)));
        }
        if (blank(timeWindow.get("durationMinutes")) && blank(timeWindow.get("end"))) {
            fields.add(field("duration", "游玩时长", "大概想玩多久，或者最晚几点结束？", "text",
                    durationSuggestions(message)));
        }
        if (blank(group.get("total")) || blank(group.get("composition"))) {
            fields.add(field("group", "同行人", "几个人同行？有没有孩子、老人或特殊照顾对象？", "text",
                    groupSuggestions(message)));
        }
        if (blank(preferences.get("budgetAmount")) && blank(preferences.get("budget"))) {
            fields.add(field("budget", "预算", "总预算大概是多少？", "number",
                    budgetSuggestions(group)));
        }
        if (blank(intent.get("scenario")) || "unknown".equals(intent.get("scenario"))) {
            fields.add(field("preferences", "核心需求", "这次最想满足什么需求？", "text",
                    preferenceSuggestions(message, group)));
        }

        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("message", "还需要补充几项可执行信息，补齐后我再查询真实地点并生成方案。");
        clarification.put("fields", fields);
        clarification.put("missingFields", fields.stream().map(field -> field.get("key")).toList());
        return clarificationAgent.clarify(planId, message, intent, clarification);
    }

    public Map<String, Object> buildClarification(Map<String, Object> intent) {
        return buildClarification(null, intent, "");
    }

    public Map<String, Object> mergeAnswers(Map<String, Object> intent, Map<String, Object> answers) {
        if (answers == null || answers.isEmpty()) {
            return intent;
        }
        Map<String, Object> merged = new LinkedHashMap<>(intent);
        Map<String, Object> location = new LinkedHashMap<>(castMap(merged.get("location")));
        Map<String, Object> group = new LinkedHashMap<>(castMap(merged.get("group")));
        Map<String, Object> timeWindow = new LinkedHashMap<>(castMap(merged.get("time_window")));
        Map<String, Object> preferences = new LinkedHashMap<>(castMap(merged.get("soft_preferences")));

        putLocation(location, answers.get("location"));
        putTime(timeWindow, answers.get("timeWindow"));
        putDuration(timeWindow, answers.get("duration"));
        putGroup(group, answers.get("group"));
        putBudget(preferences, answers.get("budget"));
        putPreferences(merged, preferences, answers.get("preferences"));
        mergeStructuredPatch(merged, preferences, answers);

        merged.put("location", location);
        merged.put("group", group);
        merged.put("time_window", timeWindow);
        merged.put("soft_preferences", preferences);
        return merged;
    }

    private Map<String, Object> field(String key, String label, String question, String type, List<String> suggestions) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", key);
        field.put("label", label);
        field.put("question", question);
        field.put("type", type);
        field.put("suggestions", suggestions);
        return field;
    }

    private void putLocation(Map<String, Object> location, Object value) {
        String text = string(value);
        if (text.isBlank()) return;
        double[] coordinates = parseCoordinates(text);
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

    private void putTime(Map<String, Object> timeWindow, Object value) {
        String text = string(value);
        if (text.isBlank()) return;
        timeWindow.put("period", text);
        timeWindow.remove("invalidReason");
        if (isInvalidTimeText(text)) {
            timeWindow.remove("start");
            timeWindow.put("invalidReason", "时间表达不清晰");
            return;
        }
        String explicitStart = extractExplicitStart(text);
        if (explicitStart != null) {
            timeWindow.put("start", explicitStart);
        }
    }

    private void putDuration(Map<String, Object> timeWindow, Object value) {
        String text = string(value);
        if (text.isBlank()) return;
        Integer minutes = extractNumber(text);
        if (minutes != null) {
            timeWindow.put("durationMinutes", minutes <= 24 ? minutes * 60 : minutes);
        } else {
            timeWindow.put("durationText", text);
        }
        if (text.contains("结束") || text.contains("前")) {
            timeWindow.put("end", text);
        }
    }

    private void putGroup(Map<String, Object> group, Object value) {
        String text = string(value);
        if (text.isBlank()) return;
        group.put("composition", text);
        if (text.contains("我自己") || text.contains("一个人") || text.contains("1人") || text.contains("单人")) {
            group.put("total", 1);
        } else if (text.contains("情侣") || text.contains("两人") || text.contains("2人") || text.contains("两个人")) {
            group.put("total", 2);
        } else if (text.contains("两") && text.contains("一")) group.put("total", 3);
        else {
            Integer number = extractNumber(text);
            if (number != null) group.put("total", number);
        }
        if (text.contains("孩子") || text.contains("儿童") || text.contains("亲子")) {
            group.put("childAge", group.getOrDefault("childAge", 5));
        }
    }

    private void putBudget(Map<String, Object> preferences, Object value) {
        String text = string(value);
        if (text.isBlank()) return;
        Integer amount = extractNumber(text);
        if (amount != null) preferences.put("budgetAmount", amount);
        preferences.put("budget", amount == null ? text : amount < 500 ? "low" : amount > 1000 ? "high" : "medium");
    }

    private void putPreferences(Map<String, Object> intent, Map<String, Object> preferences, Object value) {
        String text = string(value);
        if (text.isBlank()) return;
        preferences.put("vibe", text);
        if (text.contains("亲子") || text.contains("孩子")) intent.put("scenario", "family");
        else if (text.contains("朋友") || text.contains("聚会")) intent.put("scenario", "friends");
        else if (text.contains("情侣") || text.contains("约会")) intent.put("scenario", "date");
        else intent.put("scenario", "general");
    }

    @SuppressWarnings("unchecked")
    private void mergeStructuredPatch(Map<String, Object> intent, Map<String, Object> preferences, Map<String, Object> answers) {
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

    private void mergeList(Map<String, Object> intent, Map<String, Object> answers, String key) {
        Object value = answers.get(key);
        if (!(value instanceof List<?> patchItems)) {
            return;
        }
        List<String> merged = new ArrayList<>();
        Object existing = intent.get(key);
        if (existing instanceof List<?> existingItems) {
            existingItems.stream().map(String::valueOf).forEach(merged::add);
        }
        for (Object item : patchItems) {
            String text = String.valueOf(item);
            if (!text.isBlank() && !merged.contains(text)) {
                merged.add(text);
            }
        }
        intent.put(key, merged);
    }

    private void copyIfPresent(Map<String, Object> intent, Map<String, Object> answers, String key) {
        Object value = answers.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            intent.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private boolean hasActionableLocation(Map<String, Object> location) {
        if (hasCoordinates(location)) {
            return true;
        }
        String city = string(location.get("city"));
        String district = string(location.get("district"));
        String raw = city + district;
        if (city.isBlank() && district.isBlank()) {
            return false;
        }
        if (Boolean.TRUE.equals(location.get("needsConcreteAnchor"))) {
            return false;
        }
        return isConcreteLocation(raw);
    }

    private boolean hasCoordinates(Map<String, Object> location) {
        Object lng = location.get("lng");
        Object lat = location.get("lat");
        return lng instanceof Number && lat instanceof Number;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer extractNumber(String text) {
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
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
                .compile("(上午|早上|中午|下午|晚上|今晚)?\\s*([一二两三四五六七八九十]|1[0-2]|[1-9])点(半|[0-5]?\\d分?)?")
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

    private String locationQuestion(Map<String, Object> location) {
        String district = string(location.get("district"));
        if (!district.isBlank() && !isConcreteLocation(district)) {
            return "“" + district + "”还不能定位，请填写城市加具体商圈、地标或地址。";
        }
        return "你想在哪个城市、商圈、地标或具体地址附近安排？";
    }

    private String timeQuestion(Map<String, Object> timeWindow) {
        Object reason = timeWindow.get("invalidReason");
        if (reason != null) {
            return "开始时间有点冲突，请换成明确时间，例如 10:00 或 14:00。";
        }
        return "今天具体几点开始？";
    }

    private List<String> locationSuggestions(String message, Map<String, Object> group) {
        String city = extractCityHint(message);
        if (!city.isBlank()) {
            return List.of(city + "核心商圈附近", city + "地铁站或火车站附近", city + "你常去的地标附近");
        }
        return List.of();
    }

    private List<String> timeSuggestions(String message) {
        if (message.contains("早上") || message.contains("上午")) {
            return List.of("09:30", "10:00", "10:30");
        }
        if (message.contains("晚上") || message.contains("晚饭")) {
            return List.of("18:00", "18:30", "19:00");
        }
        if (message.contains("下午")) {
            return List.of("14:00", "15:00", "16:00");
        }
        return List.of("10:00", "14:00", "19:00");
    }

    private List<String> durationSuggestions(String message) {
        if (message.contains("晚上") || message.contains("晚饭")) {
            return List.of("2小时左右", "3小时左右", "晚饭后结束");
        }
        return List.of("3小时左右", "4小时左右", "半天");
    }

    private List<String> groupSuggestions(String message) {
        if (message.contains("情侣") || message.contains("对象") || message.contains("女朋友") || message.contains("男朋友")) {
            return List.of("情侣两人", "两个人", "夫妻两人");
        }
        if (message.contains("朋友")) {
            return List.of("2个朋友", "4个朋友", "6个朋友");
        }
        if (message.contains("孩子") || message.contains("亲子")) {
            return List.of("两个大人一个孩子", "一个大人一个孩子", "一家三口");
        }
        return List.of("情侣两人", "2-4个朋友", "两个大人一个孩子");
    }

    private List<String> budgetSuggestions(Map<String, Object> group) {
        int total = group.get("total") instanceof Number number ? number.intValue() : 2;
        if (total <= 2) {
            return List.of("300", "600", "1000");
        }
        if (total <= 4) {
            return List.of("600", "1000", "1500");
        }
        return List.of("1000", "1500", "2000");
    }

    private List<String> preferenceSuggestions(String message, Map<String, Object> group) {
        if (isCouple(group, message)) {
            return List.of("展览和晚餐", "咖啡散步和轻食", "安静约会和夜景");
        }
        if (message.contains("朋友")) {
            return List.of("朋友聚会和吃饭", "密室或桌游加晚餐", "轻松逛逛和烧烤");
        }
        if (message.contains("孩子") || message.contains("亲子")) {
            return List.of("亲子活动和晚餐", "儿童友好室内活动", "博物馆和清淡晚餐");
        }
        if (message.contains("清淡") || message.contains("展览")) {
            return List.of("文化展览和清淡晚餐", "安静展馆和咖啡", "少走路的室内安排");
        }
        return List.of("轻松逛逛和吃饭", "文化展览和咖啡", "室内活动和简餐");
    }

    private boolean isCouple(Map<String, Object> group, String message) {
        String composition = string(group.get("composition"));
        return message.contains("情侣") || message.contains("约会") || composition.contains("情侣") || composition.contains("夫妻");
    }

    private boolean hasInvalidTime(Map<String, Object> timeWindow) {
        return !blank(timeWindow.get("invalidReason"));
    }

    private boolean isInvalidTimeText(String text) {
        return text.contains("早上12点") || text.contains("上午12点") || text.contains("凌晨12点");
    }

    private boolean isConcreteLocation(String text) {
        String value = string(text);
        if (value.isBlank()) {
            return false;
        }
        if (isPlaceholderLocation(value)) {
            return false;
        }
        if (parseCoordinates(value) != null) {
            return true;
        }
        if (value.equals("附近") || value.equals("我附近") || value.equals("在我附近")
                || value.contains("当前位置") || value.contains("定位附近")) {
            return false;
        }
        if (!hasCityHint(value)) {
            return false;
        }
        if (value.contains("附近") && !(hasCityHint(value) || value.contains("区") || value.contains("县")
                || value.contains("广场") || value.contains("站") || value.contains("大学") || value.contains("公园")
                || value.contains("中心") || value.contains("商圈") || value.contains("路") || value.contains("街")
                || value.contains("号") || value.contains("巷") || value.contains("里"))) {
            return false;
        }
        return value.length() >= 2;
    }

    private boolean isPlaceholderLocation(String value) {
        String text = string(value);
        return text.contains("+")
                || text.contains("我所在城市")
                || text.contains("具体商圈")
                || text.contains("地铁站/地标")
                || text.contains("具体地址")
                || text.contains("附近道路");
    }

    private double[] parseCoordinates(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(-?\\d{2,3}\\.\\d{3,})\\s*[,，]\\s*(-?\\d{1,2}\\.\\d{3,})")
                .matcher(string(text));
        if (!matcher.find()) {
            return null;
        }
        try {
            double lng = Double.parseDouble(matcher.group(1));
            double lat = Double.parseDouble(matcher.group(2));
            if (lng < 73 || lng > 136 || lat < 3 || lat > 54) {
                return null;
            }
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
        if (value.isBlank()) {
            return "";
        }
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

    private Map<String, Object> compactIntent(Map<String, Object> intent) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("scenario", intent.get("scenario"));
        compact.put("location", intent.get("location"));
        compact.put("time_window", intent.get("time_window"));
        compact.put("group", intent.get("group"));
        return compact;
    }

    private String safeSnippet(String message) {
        return message.length() <= 80 ? message : message.substring(0, 80);
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
