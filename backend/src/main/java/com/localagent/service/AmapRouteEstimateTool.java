package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import com.localagent.model.Poi;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AmapRouteEstimateTool {
    private static final String DIRECTION_PATH = "/v3/direction/walking";

    private final ExternalClientProperties properties;
    private final MockTools mockTools;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;
    private final AmapRequestLimiter requestLimiter;
    private final boolean allowMockPoi;

    public AmapRouteEstimateTool(ExternalClientProperties properties, MockTools mockTools,
                                 ToolTraceService traceService, ObjectMapper objectMapper,
                                 AmapRequestLimiter requestLimiter,
                                 @Value("${app.allow-mock-poi:false}") boolean allowMockPoi) {
        this.properties = properties;
        this.mockTools = mockTools;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.requestLimiter = requestLimiter;
        this.allowMockPoi = allowMockPoi;
    }

    public Map<String, Object> route(UUID planId, List<Poi> stops) {
        ExternalClientProperties.Amap amap = properties.getAmap();
        if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
            return blockOrMock(planId, stops, "missing_key", null);
        }
        try {
            int travelMinutes = 0;
            double distanceKm = 0.0;
            for (int i = 1; i < stops.size(); i++) {
                Map<String, Object> segment = walkingSegment(planId, stops.get(i - 1), stops.get(i));
                travelMinutes += ((Number) segment.getOrDefault("durationSeconds", 0)).intValue() / 60;
                distanceKm += ((Number) segment.getOrDefault("distanceMeters", 0)).doubleValue() / 1000.0;
            }
            if (travelMinutes <= 0 || distanceKm <= 0.0) {
                return blockOrMock(planId, stops, "empty_route", DIRECTION_PATH);
            }
            Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                    "amap", "real", properties.getAmap().getBaseUrl() + DIRECTION_PATH, "ok"));
            output.put("travelMinutes", travelMinutes);
            output.put("distanceKm", Math.round(distanceKm * 10.0) / 10.0);
            output.put("source", "amap_walking_direction");
            traceService.trace(planId, "AmapRouteEstimateTool", "ok", System.currentTimeMillis(),
                    Map.of("stops", stops.stream().map(Poi::getName).toList()), output);
            return output;
        } catch (Exception e) {
            return blockOrMock(planId, stops, e.getClass().getSimpleName() + ": " + e.getMessage(), DIRECTION_PATH);
        }
    }

    private Map<String, Object> walkingSegment(UUID planId, Poi from, Poi to) throws Exception {
        long start = System.currentTimeMillis();
        ExternalClientProperties.Amap amap = properties.getAmap();
        String sourceUrl = amap.getBaseUrl() + DIRECTION_PATH;
        URI uri = URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                + "&origin=" + encode(from.getLng() + "," + from.getLat())
                + "&destination=" + encode(to.getLng() + "," + to.getLat()));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(amap.getTimeoutMs()))
                .build();
        HttpResponse<String> response = null;
        Map<String, Object> body = Map.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            requestLimiter.awaitSlot();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(amap.getTimeoutMs()))
                    .GET()
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (!isQpsLimited(body)) {
                break;
            }
            requestLimiter.backoff(attempt);
        }
        Map<String, Object> route = castMap(body.get("route"));
        List<Map<String, Object>> paths = castList(route.get("paths"));
        Map<String, Object> first = paths.isEmpty() ? Map.of() : paths.get(0);
        int durationSeconds = parseInt(first.get("duration"));
        int distanceMeters = parseInt(first.get("distance"));
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", "real", sourceUrl, String.valueOf(body.getOrDefault("infocode", response.statusCode()))));
        output.put("from", from.getName());
        output.put("to", to.getName());
        output.put("durationSeconds", durationSeconds);
        output.put("distanceMeters", distanceMeters);
        output.put("info", body.getOrDefault("info", ""));
        traceService.trace(planId, "AmapRouteEstimateTool", durationSeconds > 0 ? "ok" : "empty", start,
                Map.of("from", from.getName(), "to", to.getName()), output);
        return output;
    }

    private Map<String, Object> blockOrMock(UUID planId, List<Poi> stops, String reason, String sourceUrl) {
        long start = System.currentTimeMillis();
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", allowMockPoi ? "mock" : "blocked", sourceUrl == null ? "not-called" : sourceUrl, reason));
        output.put("reason", reason);
        output.put("message", BlockMessages.ROUTE_FAILED);
        traceService.trace(planId, "AmapRouteEstimateTool", allowMockPoi ? "mock" : "blocked", start,
                Map.of("stops", stops.stream().map(Poi::getName).toList()), output);
        if (allowMockPoi) {
            return mockTools.route(planId, stops);
        }
        throw new PlanBlockedException(planId, "amap", BlockMessages.ROUTE_FAILED, 503);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isQpsLimited(Map<String, Object> body) {
        return "10021".equals(String.valueOf(body.getOrDefault("infocode", "")))
                || String.valueOf(body.getOrDefault("info", "")).contains("CUQPS");
    }
}
