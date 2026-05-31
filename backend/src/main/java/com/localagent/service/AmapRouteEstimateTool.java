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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AmapRouteEstimateTool {
    private static final String WALKING_PATH = "/v3/direction/walking";
    private static final String BICYCLING_PATH = "/v3/direction/bicycling";
    private static final String DRIVING_PATH = "/v3/direction/driving";

    private final ExternalClientProperties properties;
    private final MockTools mockTools;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;
    private final AmapRequestLimiter requestLimiter;
    private final HttpClient httpClient;
    private final boolean allowMockPoi;
    private final Map<UUID, Map<String, Map<String, Object>>> segmentCaches = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AmapRouteEstimateTool(ExternalClientProperties properties, MockTools mockTools,
                                 ToolTraceService traceService, ObjectMapper objectMapper,
                                 AmapRequestLimiter requestLimiter,
                                 @Qualifier("amapHttpClient") HttpClient httpClient,
                                 @Value("${app.allow-mock-poi:false}") boolean allowMockPoi) {
        this.properties = properties;
        this.mockTools = mockTools;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.requestLimiter = requestLimiter;
        this.httpClient = httpClient;
        this.allowMockPoi = allowMockPoi;
    }

    public Map<String, Object> route(UUID planId, List<Poi> stops) {
        ExternalClientProperties.Amap amap = properties.getAmap();
        if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
            return blockOrMock(planId, stops, "missing_key", null);
        }
        try {
            Map<String, Map<String, Object>> segmentCache =
                    segmentCaches.computeIfAbsent(planId, ignored -> new ConcurrentHashMap<>());

            // 并行计算所有相邻路线段
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 1; i < stops.size(); i++) {
                final Poi from = stops.get(i - 1);
                final Poi to = stops.get(i);
                futures.add(CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return routeSegment(planId, from, to, segmentCache);
                        } catch (Exception e) {
                            return Map.of();
                        }
                    }, executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 先累加秒数，最后统一除以60，避免逐段整除精度损失
            int totalSeconds = 0;
            double distanceKm = 0.0;
            List<Integer> segmentMinutes = new ArrayList<>();
            List<String> routeModes = new ArrayList<>();
            for (CompletableFuture<Map<String, Object>> f : futures) {
                Map<String, Object> segment = f.getNow(Map.of());
                int seconds = ((Number) segment.getOrDefault("durationSeconds", 0)).intValue();
                totalSeconds += seconds;
                distanceKm += ((Number) segment.getOrDefault("distanceMeters", 0)).doubleValue() / 1000.0;
                segmentMinutes.add(seconds / 60);
                routeModes.add(String.valueOf(segment.getOrDefault("routeMode", "unknown")));
            }
            int travelMinutes = totalSeconds / 60;

            if (travelMinutes <= 0 || distanceKm <= 0.0) {
                return blockOrMock(planId, stops, "empty_route", BICYCLING_PATH);
            }
            Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                    "amap", "real", properties.getAmap().getBaseUrl() + BICYCLING_PATH, "ok"));
            output.put("travelMinutes", travelMinutes);
            output.put("distanceKm", Math.round(distanceKm * 10.0) / 10.0);
            output.put("segmentMinutes", segmentMinutes);
            output.put("routeModes", routeModes);
            output.put("source", "amap_dynamic_direction");
            traceService.trace(planId, "AmapRouteEstimateTool", "ok", System.currentTimeMillis(),
                    Map.of("stops", stops.stream().map(Poi::getName).toList()), output);
            return output;
        } catch (Exception e) {
            return blockOrMock(planId, stops, e.getClass().getSimpleName() + ": " + e.getMessage(), BICYCLING_PATH);
        }
    }

    public void clearCache(UUID planId) {
        if (planId != null) {
            segmentCaches.remove(planId);
        }
    }

    private Map<String, Object> routeSegment(UUID planId, Poi from, Poi to,
                                             Map<String, Map<String, Object>> segmentCache) throws Exception {
        String cacheKey = segmentKey(from, to);
        Map<String, Object> cached = segmentCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long start = System.currentTimeMillis();
        ExternalClientProperties.Amap amap = properties.getAmap();
        RouteMode mode = routeMode(from, to);
        String sourceUrl = amap.getBaseUrl() + mode.path();
        URI uri = URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                + "&origin=" + encode(from.getLng() + "," + from.getLat())
                + "&destination=" + encode(to.getLng() + "," + to.getLat()));

        HttpResponse<String> response = null;
        Map<String, Object> body = Map.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            requestLimiter.awaitSlot();
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(amap.getTimeoutMs()))
                        .GET()
                        .build();
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                body = objectMapper.readValue(response.body(), new TypeReference<>() {});
                if (!isQpsLimited(body)) {
                    break;
                }
                requestLimiter.backoff(attempt);
            } finally {
                requestLimiter.releaseSlot();
            }
        }
        Map<String, Object> route = castMap(body.get("route"));
        List<Map<String, Object>> paths = castList(route.get("paths"));
        Map<String, Object> first = paths.isEmpty() ? Map.of() : paths.get(0);
        int durationSeconds = parseInt(first.get("duration"));
        int distanceMeters = parseInt(first.get("distance"));
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", "real", sourceUrl, String.valueOf(body.getOrDefault("infocode", response == null ? "" : response.statusCode()))));
        output.put("from", from.getName());
        output.put("to", to.getName());
        output.put("durationSeconds", durationSeconds);
        output.put("distanceMeters", distanceMeters);
        output.put("routeMode", mode.name());
        output.put("info", body.getOrDefault("info", ""));
        traceService.trace(planId, "AmapRouteEstimateTool", durationSeconds > 0 ? "ok" : "empty", start,
                Map.of("from", from.getName(), "to", to.getName(), "routeMode", mode.name()), output);
        if (durationSeconds > 0 && distanceMeters > 0) {
            segmentCache.put(cacheKey, output);
        }
        return output;
    }

    private String segmentKey(Poi from, Poi to) {
        return routeMode(from, to).name() + ":" + from.getLng() + "," + from.getLat() + "->" + to.getLng() + "," + to.getLat();
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

    private RouteMode routeMode(Poi from, Poi to) {
        double distanceKm = directDistanceKm(from, to);
        if (distanceKm <= 1.0) {
            return new RouteMode("walking", WALKING_PATH);
        }
        if (distanceKm <= 5.0) {
            return new RouteMode("bicycling", BICYCLING_PATH);
        }
        return new RouteMode("driving", DRIVING_PATH);
    }

    private double directDistanceKm(Poi from, Poi to) {
        double radius = 6371.0;
        double dLat = Math.toRadians(to.getLat() - from.getLat());
        double dLng = Math.toRadians(to.getLng() - from.getLng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(from.getLat())) * Math.cos(Math.toRadians(to.getLat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record RouteMode(String name, String path) {}
}
