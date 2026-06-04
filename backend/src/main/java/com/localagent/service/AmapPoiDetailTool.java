package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.CacheConfig;
import com.localagent.config.ExternalClientProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

@Component
public class AmapPoiDetailTool {
    private static final String DETAIL_PATH = "/v3/place/detail";
    private static final Pattern TIME_RANGE = Pattern.compile("([01]?\\d|2[0-3]):?([0-5]\\d)\\s*[-~至到]\\s*([01]?\\d|2[0-3]):?([0-5]\\d)");

    private final ExternalClientProperties properties;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;
    private final AmapRequestLimiter requestLimiter;
    private final HttpClient httpClient;
    private final CacheManager cacheManager;

    public AmapPoiDetailTool(ExternalClientProperties properties,
                             ToolTraceService traceService,
                             ObjectMapper objectMapper,
                             AmapRequestLimiter requestLimiter,
                             @Qualifier("amapHttpClient") HttpClient httpClient,
                             CacheManager cacheManager) {
        this.properties = properties;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.requestLimiter = requestLimiter;
        this.httpClient = httpClient;
        this.cacheManager = cacheManager;
    }

    public Map<String, Object> fetchDetail(UUID planId, String poiId, String poiName) {
        long start = System.currentTimeMillis();
        ExternalClientProperties.Amap amap = properties.getAmap();
        if (isBlank(poiId)) {
            Map<String, Object> output = meta("skip", "not-called", "missing_poi_id");
            output.put("poiName", poiName);
            traceService.trace(planId, "AmapPoiDetailTool", "skip", start, Map.of("poiName", poiName), output);
            return output;
        }
        if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
            Map<String, Object> output = meta("skip", "not-called", "missing_key_or_disabled");
            output.put("poiId", poiId);
            output.put("poiName", poiName);
            traceService.trace(planId, "AmapPoiDetailTool", "skip", start, Map.of("poiId", poiId), output);
            return output;
        }
        String sourceUrl = amap.getBaseUrl() + DETAIL_PATH;
        try {
            URI uri = URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                    + "&id=" + encode(poiId)
                    + "&extensions=all");
            Map<String, Object> body = cachedDetail(poiId, uri, Math.min(amap.getDetailTimeoutMs(), amap.getTimeoutMs()));
            List<Map<String, Object>> pois = castList(body.get("pois"));
            Map<String, Object> raw = pois.isEmpty() ? Map.of() : pois.get(0);
            Map<String, Object> detail = new LinkedHashMap<>(meta("real", sourceUrl,
                    String.valueOf(body.getOrDefault("infocode", body.getOrDefault("httpStatus", "")))));
            detail.put("poiId", poiId);
            detail.put("poiName", stringValue(raw.get("name"), poiName));
            detail.put("openingHours", openingHours(raw));
            detail.put("timeSlots", parseTimeSlots(String.valueOf(detail.getOrDefault("openingHours", ""))));
            detail.put("rawAvailable", !raw.isEmpty());
            traceService.trace(planId, "AmapPoiDetailTool", raw.isEmpty() ? "empty" : "ok", start,
                    Map.of("poiId", poiId), detail);
            return detail;
        } catch (Exception e) {
            Map<String, Object> output = meta("fallback", sourceUrl, e.getClass().getSimpleName() + ": " + e.getMessage());
            output.put("poiId", poiId);
            output.put("poiName", poiName);
            traceService.trace(planId, "AmapPoiDetailTool", "fallback", start, Map.of("poiId", poiId), output);
            return output;
        }
    }

    public boolean isClosedAt(Map<String, Object> detail, LocalTime time) {
        List<Map<String, Object>> slots = castList(detail.get("timeSlots"));
        if (slots.isEmpty() || time == null) {
            return false;
        }
        return slots.stream().noneMatch(slot -> within(time,
                LocalTime.parse(String.valueOf(slot.get("start"))),
                LocalTime.parse(String.valueOf(slot.get("end")))));
    }

    private boolean within(LocalTime time, LocalTime start, LocalTime end) {
        if (end.isBefore(start)) {
            return !time.isBefore(start) || !time.isAfter(end);
        }
        return !time.isBefore(start) && !time.isAfter(end);
    }

    private List<Map<String, Object>> parseTimeSlots(String text) {
        if (text == null || text.isBlank() || "[]".equals(text)) {
            return List.of();
        }
        List<Map<String, Object>> slots = new ArrayList<>();
        Matcher matcher = TIME_RANGE.matcher(text.replace("：", ":"));
        while (matcher.find()) {
            String start = String.format("%02d:%s", Integer.parseInt(matcher.group(1)), matcher.group(2));
            String end = String.format("%02d:%s", Integer.parseInt(matcher.group(3)), matcher.group(4));
            slots.add(Map.of("start", start, "end", end));
        }
        return slots;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cachedDetail(String poiId, URI uri, int timeoutMs) throws Exception {
        Cache cache = cacheManager.getCache(CacheConfig.POI_DETAIL_CACHE);
        if (cache != null) {
            Cache.ValueWrapper cached = cache.get(poiId);
            if (cached != null && cached.get() instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        requestLimiter.awaitSlot();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            body.putIfAbsent("httpStatus", response.statusCode());
            if (cache != null) {
                cache.put(poiId, body);
            }
            return body;
        } finally {
            requestLimiter.releaseSlot();
        }
    }

    private String openingHours(Map<String, Object> raw) {
        Map<String, Object> bizExt = castMap(raw.get("biz_ext"));
        for (String key : List.of("opentime", "open_time", "opening_hours", "openTime")) {
            String value = stringValue(bizExt.get(key), "");
            if (!value.isBlank()) {
                return value;
            }
        }
        for (String key : List.of("opentime", "open_time", "opening_hours", "openTime")) {
            String value = stringValue(raw.get(key), "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> meta(String mode, String sourceUrl, String status) {
        return new LinkedHashMap<>(traceService.externalMeta("amap", mode, sourceUrl, status));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "[]".equals(text) ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }
}
