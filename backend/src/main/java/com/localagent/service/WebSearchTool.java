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
            Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                    "tavily", "real", sourceUrl, String.valueOf(response.statusCode())));
            output.put("query", requestBody.get("query"));
            output.put("count", results.size());
            output.put("results", results.stream().limit(3).map(this::compactResult).toList());
            traceService.trace(planId, "WebSearchTool", response.statusCode() < 400 ? "ok" : "external_error", start,
                    Map.of("provider", "tavily", "maxResults", search.getMaxResults()), output);
            return results;
        } catch (Exception e) {
            traceFallback(planId, intent, e.getClass().getSimpleName() + ": " + e.getMessage(), sourceUrl);
            return List.of();
        }
    }

    private String buildQuery(Map<String, Object> intent, String message) {
        String city = String.valueOf(castMap(intent.get("location")).getOrDefault("city", "\u5927\u8fde"));
        String scenario = String.valueOf(intent.getOrDefault("scenario", "\u672c\u5730\u751f\u6d3b"));
        return city + " " + scenario + " \u4eca\u5929\u4e0b\u5348 \u4eb2\u5b50 \u670b\u53cb \u6d3b\u52a8 \u9910\u5385 " + safeSnippet(message);
    }

    private Map<String, Object> compactResult(Map<String, Object> result) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("title", result.getOrDefault("title", ""));
        compact.put("url", result.getOrDefault("url", ""));
        compact.put("score", result.getOrDefault("score", 0));
        compact.put("content", result.getOrDefault("content", ""));
        return compact;
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
