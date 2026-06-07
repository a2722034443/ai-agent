package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.model.PlanOption;
import com.localagent.model.PlanSession;
import com.localagent.repo.PlanOptionRepository;
import com.localagent.repo.PlanSessionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuardService {
    private final PlanSessionRepository planSessionRepository;
    private final PlanOptionRepository planOptionRepository;
    private final ObjectMapper objectMapper;

    public GuardService(PlanSessionRepository planSessionRepository,
                        PlanOptionRepository planOptionRepository,
                        ObjectMapper objectMapper) {
        this.planSessionRepository = planSessionRepository;
        this.planOptionRepository = planOptionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(UUID planId) {
        if (planId == null) {
            return legacyMockStatus();
        }
        PlanSession session = planSessionRepository.findById(planId).orElseThrow(NoSuchElementException::new);
        Map<String, Object> result = fromJson(session.getResultJson());
        Map<String, Object> execution = fromJson(session.getExecutionJson());
        Map<String, Object> option = selectedOption(planId, execution);
        Map<String, Object> weather = mapValue(result.get("weather"));
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(weatherStep(weather));
        steps.add(routeStep(option));
        steps.add(merchantStep(option));

        String overall = steps.stream().anyMatch(step -> "blocked".equals(step.get("status")))
                ? "BLOCKED"
                : steps.stream().anyMatch(step -> "warn".equals(step.get("status")))
                ? "WATCHING"
                : "NORMAL";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", planId.toString());
        payload.put("mode", modeFor(steps));
        payload.put("provider", providerFor(steps));
        payload.put("status", overall);
        payload.put("summary", summaryFor(steps));
        payload.put("steps", steps);
        payload.put("fallbackOptions", fallbackOptions(option));
        payload.put("fallback", "如遇天气、路线或商家状态变化，AI 会优先给出室内替代、同预算餐厅或更近路线。");
        return payload;
    }

    private Map<String, Object> selectedOption(UUID planId, Map<String, Object> execution) {
        Integer selectedRank = null;
        Object rank = execution.get("selectedRank");
        if (rank instanceof Number number) {
            selectedRank = number.intValue();
        }
        List<PlanOption> options = planOptionRepository.findByPlanSessionIdOrderByRankNo(planId);
        if (options.isEmpty()) {
            return Map.of();
        }
        Integer finalSelectedRank = selectedRank;
        PlanOption selected = options.stream()
                .filter(option -> finalSelectedRank != null && option.getRankNo() == finalSelectedRank)
                .findFirst()
                .orElse(options.get(0));
        return selected.getOptionJson() == null ? Map.of() : fromJson(selected.getOptionJson());
    }

    private Map<String, Object> weatherStep(Map<String, Object> weather) {
        String mode = String.valueOf(weather.getOrDefault("mode", weather.getOrDefault("available", false).equals(false) ? "skip" : "real"));
        String provider = String.valueOf(weather.getOrDefault("provider", "amap"));
        String suggestion = String.valueOf(weather.getOrDefault("suggestion", "天气暂不可用，建议出发前自行确认天气变化。"));
        String status = String.valueOf(weather.getOrDefault("weather", "")).contains("雨") ? "warn"
                : Boolean.FALSE.equals(weather.get("available")) ? "skip" : "done";
        return step("天气", status, suggestion, provider, mode, mode);
    }

    private Map<String, Object> routeStep(Map<String, Object> option) {
        Map<String, Object> route = mapValue(option.get("route"));
        String mode = String.valueOf(route.getOrDefault("mode", route.getOrDefault("source", "")).toString().contains("mock") ? "mock" : "real");
        String provider = String.valueOf(route.getOrDefault("provider", "amap"));
        String status = route.isEmpty() ? "skip" : "done";
        String message = route.isEmpty()
                ? "路线状态暂不可用，建议出发前再次确认。"
                : "路线约 " + route.getOrDefault("distanceKm", "?") + "km，交通约 "
                + route.getOrDefault("travelMinutes", "?") + " 分钟。";
        return step("路线", status, message, provider, mode, mode);
    }

    private Map<String, Object> merchantStep(Map<String, Object> option) {
        List<Map<String, Object>> timeline = listValue(option.get("timeline"));
        if (timeline.isEmpty()) {
            return step("商家状态", "skip", "暂无可核验地点，请重新生成方案。", "amap", "skip", "skip");
        }
        String dining = timeline.stream()
                .filter(item -> "餐饮".equals(String.valueOf(item.get("type"))))
                .map(item -> String.valueOf(item.getOrDefault("name", "")))
                .filter(name -> !name.isBlank())
                .findFirst()
                .orElse("餐厅");
        return step("商家状态", "skip",
                dining + "营业和座位状态待出发前确认；当前演示环境未接入真实库存。",
                "amap", "skip", "skip");
    }

    private Map<String, Object> step(String name, String status, String message,
                                     String provider, String mode, String source) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", status);
        item.put("message", message);
        item.put("provider", provider);
        item.put("mode", mode);
        item.put("source", source);
        return item;
    }

    private List<Map<String, Object>> fallbackOptions(Map<String, Object> option) {
        List<Map<String, Object>> timeline = listValue(option.get("timeline"));
        String dining = timeline.stream()
                .filter(item -> "餐饮".equals(String.valueOf(item.get("type"))))
                .map(item -> String.valueOf(item.getOrDefault("name", "")))
                .filter(name -> !name.isBlank())
                .findFirst()
                .orElse("当前餐厅");
        return List.of(
                Map.of("type", "weather", "message", "如遇下雨，优先改为室内亲子/展览点。"),
                Map.of("type", "merchant", "message", dining + "满位时，替换为同预算、同路线方向餐厅。")
        );
    }

    private String modeFor(List<Map<String, Object>> steps) {
        if (steps.stream().anyMatch(step -> "real".equals(step.get("mode")))) {
            return "mixed";
        }
        if (steps.stream().allMatch(step -> "skip".equals(step.get("mode")))) {
            return "skip";
        }
        return "mock";
    }

    private String providerFor(List<Map<String, Object>> steps) {
        return steps.stream()
                .map(step -> String.valueOf(step.getOrDefault("provider", "")))
                .filter(provider -> !provider.isBlank())
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("local");
    }

    private String summaryFor(List<Map<String, Object>> steps) {
        long skipped = steps.stream().filter(step -> "skip".equals(step.get("status"))).count();
        if (skipped == steps.size()) {
            return "守护已开启；当前环境缺少真实状态源，出发前会提示你复核天气、路线和商家状态。";
        }
        return "守护已开启；已按可用数据检查天气、路线和商家状态。";
    }

    private Map<String, Object> legacyMockStatus() {
        return Map.of(
                "mode", "mock",
                "provider", "local",
                "status", "WATCHING",
                "summary", "守护已开启；建议传入 planId 获取按方案核验的状态。",
                "steps", List.of(
                        step("天气", "done", "天气适合出行。", "local", "mock", "mock"),
                        step("路线", "done", "路线暂无异常。", "local", "mock", "mock"),
                        step("商家状态", "skip", "商家状态需要绑定 planId 后核验。", "local", "skip", "skip")
                ),
                "fallbackOptions", List.of()
        );
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }
}
