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
public class PlanGeneratorAgent {
    private static final String SYSTEM_PROMPT = """
            你是本地生活方案生成 Agent。你只能使用用户提供的真实候选地点生成方案，不能编造地点。
            所有展示文案必须是中文。输出必须是 JSON 数组，不要输出解释。
            每个方案字段：
            name、tagline、timeline、totalMinutes、budgetEstimate、fitReasons、riskNotes、executionList。
            timeline 每项字段：time、type、name、subtype、address、durationMinutes、avgPrice、rating、reason、lng、lat。
            type 只能使用：活动、餐饮、补充。
            输入中的 routeCandidates 已经由高德路线计算完成。你必须从 routeCandidates 中挑选并改写成方案，
            不能替换、增加或编造任何地点，timeline 中的 name 必须和候选完全一致。
            至少生成 3 套方案，每套至少包含活动、餐饮、补充三段，总时长 4-6 小时。
            """;

    private final MimoClient mimoClient;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;

    public PlanGeneratorAgent(MimoClient mimoClient, ToolTraceService traceService, ObjectMapper objectMapper) {
        this.mimoClient = mimoClient;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> generate(UUID planId, Map<String, Object> intent, List<Map<String, Object>> routeCandidates,
                                              List<Map<String, Object>> webEvidence) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", intent);
            payload.put("routeCandidates", routeCandidates);
            payload.put("webEvidence", webEvidence.stream().limit(3).map(this::compactEvidence).toList());
            String content = mimoClient.complete(SYSTEM_PROMPT, objectMapper.writeValueAsString(payload));
            List<Map<String, Object>> options = objectMapper.readValue(extractJsonArray(content), new TypeReference<>() {});
            traceService.trace(planId, "PlanGeneratorAgent", "ok", start,
                    Map.of("routeCandidateCount", routeCandidates.size(), "evidenceCount", webEvidence.size()),
                    Map.of("provider", "mimo", "mode", "real", "count", options.size()));
            return normalize(options);
        } catch (Exception e) {
            traceService.trace(planId, "PlanGeneratorAgent", "blocked", start,
                    Map.of("routeCandidateCount", routeCandidates.size()),
                    Map.of("provider", "mimo", "mode", "blocked", "reason", e.getMessage(), "message", BlockMessages.LLM_FAILED));
            throw new PlanBlockedException(planId, "mimo", BlockMessages.LLM_FAILED, 503);
        }
    }

    private Map<String, Object> compactEvidence(Map<String, Object> evidence) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("title", limit(String.valueOf(evidence.getOrDefault("title", "")), 60));
        compact.put("url", evidence.getOrDefault("url", ""));
        compact.put("content", limit(String.valueOf(evidence.getOrDefault("content", "")), 120));
        return compact;
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength);
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> options) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int i = 0; i < options.size() && normalized.size() < 3; i++) {
            Map<String, Object> option = new LinkedHashMap<>(options.get(i));
            option.put("rank", normalized.size() + 1);
            option.putIfAbsent("fitReasons", List.of("符合用户约束", "路线安排完整"));
            option.putIfAbsent("riskNotes", List.of("建议出发前再次确认营业时间"));
            option.putIfAbsent("executionList", List.of("购票", "订座", "分享行程"));
            normalized.add(option);
        }
        if (normalized.size() < 3) {
            throw new IllegalArgumentException("MiMo 未生成 3 套方案");
        }
        return normalized;
    }

    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("MiMo 输出不是 JSON 数组");
        }
        return content.substring(start, end + 1);
    }
}
