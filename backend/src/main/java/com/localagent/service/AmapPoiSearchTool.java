package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import com.localagent.model.Poi;
import com.localagent.model.PoiType;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AmapPoiSearchTool {
    private static final String SEARCH_PATH = "/v3/place/text";

    private final ExternalClientProperties properties;
    private final MockTools mockTools;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;
    private final AmapRequestLimiter requestLimiter;
    private final boolean allowMockPoi;

    public AmapPoiSearchTool(ExternalClientProperties properties, MockTools mockTools,
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

    public List<Poi> searchPois(UUID planId, Map<String, Object> intent) {
        ExternalClientProperties.Amap amap = properties.getAmap();
        if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
            return blockOrMock(planId, intent, "missing_key", null);
        }
        try {
            List<Poi> pois = new ArrayList<>();
            pois.addAll(searchByKeyword(planId, intent, PoiType.ENTERTAINMENT, activityKeyword(intent)));
            if (pois.stream().noneMatch(poi -> poi.getType() == PoiType.ENTERTAINMENT)) {
                pois.addAll(searchByKeyword(planId, intent, PoiType.ENTERTAINMENT, fallbackActivityKeyword(intent)));
            }
            pois.addAll(searchByKeyword(planId, intent, PoiType.CULTURE, "展览 文化 剧场 博物馆"));
            pois.addAll(searchByKeyword(planId, intent, PoiType.DINING, diningKeyword(intent)));
            if (pois.size() < 6) {
                return blockOrMock(planId, intent, "empty_or_too_few_results", SEARCH_PATH);
            }
            return pois;
        } catch (Exception e) {
            return blockOrMock(planId, intent, e.getClass().getSimpleName() + ": " + e.getMessage(), SEARCH_PATH);
        }
    }

    public List<Poi> searchByKeyword(UUID planId, Map<String, Object> intent, String keyword, PoiType type) throws Exception {
        return searchByKeyword(planId, intent, type, keyword);
    }

    private List<Poi> searchByKeyword(UUID planId, Map<String, Object> intent, PoiType type, String keyword) throws Exception {
        long start = System.currentTimeMillis();
        ExternalClientProperties.Amap amap = properties.getAmap();
        String sourceUrl = amap.getBaseUrl() + SEARCH_PATH;
        URI uri = URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                + "&keywords=" + encode(keyword)
                + "&city=" + encode(amap.getCity())
                + "&offset=8&page=1&extensions=base");
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
        List<Map<String, Object>> rawPois = castList(body.get("pois"));
        List<Poi> mapped = rawPois.stream()
                .map(raw -> ExternalPoiMapper.fromAmap(raw, type))
                .filter(poi -> poi.getLng() != 0.0 && poi.getLat() != 0.0 && isChineseVisible(poi.getName()))
                .limit(5)
                .toList();

        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", "real", sourceUrl, String.valueOf(body.getOrDefault("infocode", response.statusCode()))));
        output.put("keyword", keyword);
        output.put("count", mapped.size());
        output.put("info", body.getOrDefault("info", ""));
        output.put("apiStatus", body.getOrDefault("status", ""));
        traceService.trace(planId, "AmapPoiSearchTool", mapped.isEmpty() ? "empty" : "ok", start,
                Map.of("keyword", keyword, "city", amap.getCity(), "type", type.name()), output);
        return mapped;
    }

    private List<Poi> blockOrMock(UUID planId, Map<String, Object> intent, String reason, String sourceUrl) {
        long start = System.currentTimeMillis();
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", allowMockPoi ? "mock" : "blocked", sourceUrl == null ? "not-called" : sourceUrl, reason));
        output.put("reason", reason);
        output.put("message", BlockMessages.AMAP_FAILED);
        traceService.trace(planId, "AmapPoiSearchTool", allowMockPoi ? "mock" : "blocked", start, intent, output);
        if (allowMockPoi) {
            return mockTools.searchPois(planId, intent);
        }
        String message = "empty_or_too_few_results".equals(reason) ? BlockMessages.NO_POI_FOUND : BlockMessages.AMAP_FAILED;
        throw new PlanBlockedException(planId, "amap", message, 503);
    }

    private String activityKeyword(Map<String, Object> intent) {
        if ("friends".equals(intent.get("scenario"))) {
            return "KTV 密室 桌游";
        }
        return "亲子 乐园 博物馆";
    }

    private String fallbackActivityKeyword(Map<String, Object> intent) {
        if ("friends".equals(intent.get("scenario"))) {
            return "娱乐 KTV";
        }
        return "儿童乐园 科技馆";
    }

    private String diningKeyword(Map<String, Object> intent) {
        String constraints = String.valueOf(intent.getOrDefault("hard_constraints", List.of()));
        if (constraints.contains("low_calorie_preferred") || constraints.contains("低卡") || constraints.contains("减肥")) {
            return "轻食 健康餐 沙拉";
        }
        if ("friends".equals(intent.get("scenario"))) {
            return "聚餐 烧烤 餐厅";
        }
        return "亲子 餐厅";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
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

    private boolean isChineseVisible(String value) {
        return value != null && value.codePoints().anyMatch(code -> code >= 0x4E00 && code <= 0x9FFF);
    }
}
