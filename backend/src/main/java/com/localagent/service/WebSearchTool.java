package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool {
    private static final String TAVILY_SEARCH_PATH = "/search";

    private final ExternalClientProperties properties;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;

    public WebSearchTool(ExternalClientProperties properties, ToolTraceService traceService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> verifyPlanningContext(UUID planId, Map<String, Object> intent, String message) {
        ExternalClientProperties.Search search = properties.getSearch();
        if (!search.isEnabled() || !"tavily".equalsIgnoreCase(search.getProvider()) || isBlank(search.getTavilyApiKey())) {
            traceFallback(planId, intent, "missing_key_or_disabled", "not-called");
            return List.of();
        }
        long start = System.currentTimeMillis();
        String sourceUrl = search.getBaseUrl() + TAVILY_SEARCH_PATH;
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("query", buildQuery(intent, message));
            requestBody.put("topic", "general");
            requestBody.put("search_depth", "basic");
            requestBody.put("max_results", search.getMaxResults());
            requestBody.put("include_answer", false);
            requestBody.put("include_raw_content", false);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(search.getTimeoutMs()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
                    .timeout(Duration.ofMillis(search.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + search.getTavilyApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> results = castList(body.get("results"));
            List<Map<String, Object>> filtered = results.stream()
                    .filter(this::isUsefulResult)
                    .limit(search.getMaxResults())
                    .toList();
            Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                    "tavily", "real", sourceUrl, String.valueOf(response.statusCode())));
            output.put("query", requestBody.get("query"));
            output.put("count", filtered.size());
            output.put("rawCount", results.size());
            output.put("results", filtered.stream().limit(3).map(this::compactResult).toList());
            traceService.trace(planId, "WebSearchTool", response.statusCode() < 400 ? "ok" : "external_error", start,
                    Map.of("provider", "tavily", "maxResults", search.getMaxResults()), output);
            return filtered;
        } catch (Exception e) {
            traceFallback(planId, intent, e.getClass().getSimpleName() + ": " + e.getMessage(), sourceUrl);
            return List.of();
        }
    }

    private String buildQuery(Map<String, Object> intent, String message) {
        Map<String, Object> location = castMap(intent.get("location"));
        String city = String.valueOf(location.getOrDefault("city", ""));
        String district = String.valueOf(location.getOrDefault("district", ""));
        String scenario = scenarioText(String.valueOf(intent.getOrDefault("scenario", "本地生活")));
        String area = !city.isBlank() && !"null".equals(city)
                ? ((district.isBlank() || "null".equals(district)) ? city : city + " " + district)
                : ((district.isBlank() || "null".equals(district) || district.contains("当前位置")) ? "" : district);
        return area + " " + scenario + " 营业 排队 评价 近期活动 " + safeSnippet(message);
    }

    private Map<String, Object> compactResult(Map<String, Object> result) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("title", result.getOrDefault("title", ""));
        compact.put("url", result.getOrDefault("url", ""));
        compact.put("score", result.getOrDefault("score", 0));
        compact.put("content", result.getOrDefault("content", ""));
        return compact;
    }

    private boolean isUsefulResult(Map<String, Object> result) {
        String title = String.valueOf(result.getOrDefault("title", ""));
        String url = String.valueOf(result.getOrDefault("url", ""));
        String content = String.valueOf(result.getOrDefault("content", ""));
        double score = parseScore(result.get("score"));
        if (title.isBlank() || url.isBlank() || content.isBlank()) {
            return false;
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return false;
        }
        if (url.endsWith(".xls") || url.endsWith(".xlsx") || url.endsWith(".zip") || url.endsWith(".rar")) {
            return false;
        }
        return score >= 0.08 && !looksBinary(content);
    }

    private boolean looksBinary(String content) {
        if (content.length() < 20) {
            return false;
        }
        long suspicious = content.chars()
                .filter(ch -> ch < 9 || (ch > 13 && ch < 32) || ch == 127 || ch == 65533)
                .count();
        return suspicious > 0 || content.chars().filter(ch -> ch == '{' || ch == '}' || ch == '\\').count() > content.length() / 8;
    }

    private double parseScore(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String scenarioText(String scenario) {
        return switch (scenario) {
            case "family" -> "亲子活动 餐厅";
            case "friends" -> "朋友聚会 餐厅 娱乐";
            case "couple" -> "情侣约会 餐厅 展览";
            default -> "本地活动 餐厅";
        };
    }

    private void traceFallback(UUID planId, Map<String, Object> intent, String reason, String sourceUrl) {
        long start = System.currentTimeMillis();
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                properties.getSearch().getProvider(), "fallback", sourceUrl, reason));
        output.put("reason", reason);
        traceService.trace(planId, "WebSearchTool", "fallback", start, intent, output);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String safeSnippet(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 40 ? message : message.substring(0, 40);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
