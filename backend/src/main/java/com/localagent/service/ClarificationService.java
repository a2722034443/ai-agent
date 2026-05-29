package com.localagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClarificationService {
    public Map<String, Object> buildClarification(Map<String, Object> intent) {
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> location = castMap(intent.get("location"));
        Map<String, Object> group = castMap(intent.get("group"));
        Map<String, Object> timeWindow = castMap(intent.get("time_window"));
        Map<String, Object> preferences = castMap(intent.get("soft_preferences"));

        if (!hasActionableLocation(location)) {
            fields.add(field("location", "地点", "你想在哪个城市或区域附近安排？", "text",
                    List.of("大连星海广场附近", "大连中山区", "大连高新区")));
        }
        if (blank(timeWindow.get("start"))) {
            fields.add(field("timeWindow", "开始时间", "今天具体几点开始？", "choice",
                    List.of("14:00", "15:00", "16:00")));
        }
        if (blank(timeWindow.get("durationMinutes")) && blank(timeWindow.get("end"))) {
            fields.add(field("duration", "游玩时长", "大概想玩多久，或者最晚几点结束？", "text",
                    List.of("3小时左右", "4小时左右", "晚饭后结束")));
        }
        if (blank(group.get("total")) || blank(group.get("composition"))) {
            fields.add(field("group", "同行人", "几个人同行？有没有孩子、老人或特殊照顾对象？", "text",
                    List.of("两个大人一个孩子", "4个朋友", "情侣两人")));
        }
        if (blank(preferences.get("budgetAmount")) && blank(preferences.get("budget"))) {
            fields.add(field("budget", "预算", "总预算大概是多少？", "number",
                    List.of("300", "600", "1000")));
        }
        if (blank(intent.get("scenario")) || "unknown".equals(intent.get("scenario"))) {
            fields.add(field("preferences", "核心需求", "这次最想满足什么需求？", "text",
                    List.of("亲子活动和晚餐", "朋友聚会和吃饭", "展览和清淡晚餐")));
        }

        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("message", "还需要补充几项信息，补齐后我再查询真实地点并生成方案。");
        clarification.put("fields", fields);
        return clarification;
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
        location.put("city", text.contains("大连") ? "大连" : text);
        location.put("district", text);
        location.put("radius", "nearby");
    }

    private void putTime(Map<String, Object> timeWindow, Object value) {
        String text = string(value);
        if (text.isBlank()) return;
        timeWindow.put("period", text);
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
        if (text.contains("两") && text.contains("一")) group.put("total", 3);
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
        String city = string(location.get("city"));
        String district = string(location.get("district"));
        String raw = city + district;
        if (city.isBlank() && district.isBlank()) {
            return false;
        }
        return !(raw.equals("附近") || raw.equals("我附近") || raw.equals("附近附近") || raw.contains("当前位置"));
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
