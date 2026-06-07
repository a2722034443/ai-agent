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
            你是本地生活方案表达 Agent。你不是 POI 搜索工具，也不是事实来源。

            只允许使用输入中的 routeCandidates 和 webEvidence 改写方案文案：
            - 不得新增、替换、编造任何 POI。
            - timeline 中的 name、address、lng、lat 必须来自候选地点，保持原文。
            - 可以优化 name、tagline、fitReasons、riskNotes、executionList，让方案像专业管家写出来。
            - 必须说明真实约束：路线、预算、儿童/老人/忌口/天气/排队/停车/营业状态不确定性。
            - 如果候选不足，不能硬凑。

            只输出 JSON 数组。
            每个方案字段：name、tagline、timeline、totalMinutes、budgetEstimate、fitReasons、riskNotes、executionList。
            timeline 字段：time、type、name、subtype、address、durationMinutes、avgPrice、rating、reason、lng、lat。
            type 只能是：活动、餐饮、补充。
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
            int requestedCount = planCount(intent);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", intent);
            payload.put("routeCandidates", routeCandidates);
            payload.put("webEvidence", webEvidence.stream().limit(3).map(this::compactEvidence).toList());
            payload.put("requestedPlanCount", requestedCount);
            MimoClient.CompletionResult completion = mimoClient.completeWithMeta(SYSTEM_PROMPT, objectMapper.writeValueAsString(payload));
            String content = completion.content();
            List<Map<String, Object>> options = objectMapper.readValue(extractJsonArray(content), new TypeReference<>() {});
            traceService.trace(planId, "PlanGeneratorAgent", "ok", start,
                    Map.of("routeCandidateCount", routeCandidates.size(), "evidenceCount", webEvidence.size()),
                    Map.of("provider", "mimo", "mode", "real", "lane", completion.lane(),
                            "model", completion.model(), "llmDurationMs", completion.durationMs(),
                            "fallbackReason", completion.fallbackReason(), "finishReason", completion.finishReason(),
                            "responseSource", completion.responseSource(), "count", options.size()));
            return normalize(options, requestedCount);
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

    private List<Map<String, Object>> normalize(List<Map<String, Object>> options, int requestedCount) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int i = 0; i < options.size() && normalized.size() < requestedCount; i++) {
            Map<String, Object> option = new LinkedHashMap<>(options.get(i));
            option.put("rank", normalized.size() + 1);
            option.putIfAbsent("fitReasons", List.of("符合用户约束", "路线安排完整", "地点来自真实候选"));
            option.putIfAbsent("riskNotes", List.of("建议出发前再次确认营业时间、座位和票务状态"));
            option.putIfAbsent("executionList", List.of("订座", "购票", "分享行程"));
            normalized.add(option);
        }
        if (normalized.size() < requestedCount) {
            throw new IllegalArgumentException("MiMo 未生成用户要求数量的方案");
        }
        return normalized;
    }

    private int planCount(Map<String, Object> intent) {
        Object value = intent.get("requestedPlanCount");
        if (value instanceof Number number) {
            return Math.max(1, Math.min(5, number.intValue()));
        }
        return 3;
    }

    private String extractJsonArray(String content) {
        int start = content == null ? -1 : content.indexOf('[');
        int end = content == null ? -1 : content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("MiMo 输出不是 JSON 数组");
        }
        return content.substring(start, end + 1);
    }
}
