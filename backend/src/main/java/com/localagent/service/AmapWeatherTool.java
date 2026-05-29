package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import java.net.URI;
import java.net.URLEncoder;
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
public class AmapWeatherTool {
    private static final String WEATHER_PATH = "/v3/weather/weatherInfo";

    private final ExternalClientProperties properties;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;
    private final AmapRequestLimiter requestLimiter;

    public AmapWeatherTool(ExternalClientProperties properties, ToolTraceService traceService,
                           ObjectMapper objectMapper, AmapRequestLimiter requestLimiter) {
        this.properties = properties;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.requestLimiter = requestLimiter;
    }

    public Map<String, Object> weather(UUID planId, Map<String, Object> intent) {
        ExternalClientProperties.Amap amap = properties.getAmap();
        String city = city(intent, amap.getCity());
        if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
            return fallback(planId, city, "missing_key_or_disabled", "not-called");
        }
        long start = System.currentTimeMillis();
        String sourceUrl = amap.getBaseUrl() + WEATHER_PATH;
        try {
            URI uri = URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                    + "&city=" + encode(city)
                    + "&extensions=base");
            requestLimiter.awaitSlot();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(amap.getTimeoutMs()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(amap.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> lives = castList(body.get("lives"));
            Map<String, Object> live = lives.isEmpty() ? Map.of() : lives.get(0);
            Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                    "amap", "real", sourceUrl, String.valueOf(body.getOrDefault("infocode", response.statusCode()))));
            output.put("city", city);
            output.put("weather", live.getOrDefault("weather", ""));
            output.put("temperature", live.getOrDefault("temperature", ""));
            output.put("windDirection", live.getOrDefault("winddirection", ""));
            output.put("windPower", live.getOrDefault("windpower", ""));
            output.put("humidity", live.getOrDefault("humidity", ""));
            output.put("reportTime", live.getOrDefault("reporttime", ""));
            output.put("suggestion", suggestion(output));
            traceService.trace(planId, "AmapWeatherTool", lives.isEmpty() ? "empty" : "ok", start,
                    Map.of("city", city), output);
            return output;
        } catch (Exception e) {
            return fallback(planId, city, e.getClass().getSimpleName() + ": " + e.getMessage(), sourceUrl);
        }
    }

    private Map<String, Object> fallback(UUID planId, String city, String reason, String sourceUrl) {
        long start = System.currentTimeMillis();
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta("amap", "fallback", sourceUrl, reason));
        output.put("city", city);
        output.put("available", false);
        output.put("suggestion", "天气暂不可用，建议出发前自行确认天气变化。");
        output.put("reason", reason);
        traceService.trace(planId, "AmapWeatherTool", "fallback", start, Map.of("city", city), output);
        return output;
    }

    private String suggestion(Map<String, Object> weather) {
        String text = String.valueOf(weather.getOrDefault("weather", ""));
        String temperature = String.valueOf(weather.getOrDefault("temperature", ""));
        if (text.contains("雨") || text.contains("雪")) {
            return "天气可能影响户外体验，建议优先选择室内活动并预留交通时间。";
        }
        try {
            int temp = Integer.parseInt(temperature);
            if (temp >= 30) return "气温较高，建议减少暴晒和长时间户外停留。";
            if (temp <= 5) return "气温较低，建议优先选择室内活动并注意保暖。";
        } catch (Exception ignored) {
        }
        return "天气条件可参考，方案会兼顾室内外安排。";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String city(Map<String, Object> intent, String fallback) {
        Object value = castMap(intent.get("location")).get("city");
        return isBlank(String.valueOf(value)) || "null".equals(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
