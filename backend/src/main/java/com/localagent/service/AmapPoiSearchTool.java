package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import com.localagent.dto.ApiDtos.NearbyPoiCategoryRequest;
import com.localagent.dto.ApiDtos.NearbyPoiRequest;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AmapPoiSearchTool {
    private static final String SEARCH_PATH = "/v3/place/text";
    private static final String AROUND_PATH = "/v3/place/around";
    private static final String GEOCODE_PATH = "/v3/geocode/geo";
    private static final int MAX_KEYWORDS_PER_TYPE = 3;
    private static final int POI_OFFSET = 15;
    private static final int AROUND_RADIUS = 8000;
    private static final int MIN_DEDUPED_SIZE = 9;
    private static final int MAX_REQUEST_TIMEOUT_MS = 1800;

    private final ExternalClientProperties properties;
    private final MockTools mockTools;
    private final ToolTraceService traceService;
    private final ObjectMapper objectMapper;
    private final AmapRequestLimiter requestLimiter;
    private final HttpClient httpClient;
    private final boolean allowMockPoi;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AmapPoiSearchTool(ExternalClientProperties properties, MockTools mockTools,
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

    public List<Poi> searchPois(UUID planId, Map<String, Object> intent) {
        ExternalClientProperties.Amap amap = properties.getAmap();
        if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
            return blockOrMock(planId, intent, "missing_key", null);
        }
        try {
            Anchor anchor = resolveAnchor(planId, intent);

            // 并行构建所有搜索任务
            List<CompletableFuture<List<Poi>>> futures = new ArrayList<>();

            for (String keyword : keywords(intent, "activityKeywords", activityKeyword(intent))) {
                final String kw = keyword;
                futures.add(CompletableFuture.supplyAsync(
                    () -> safeSearch(planId, intent, PoiType.ENTERTAINMENT, kw, anchor), executor));
            }
            for (String keyword : keywords(intent, "activityKeywords", "展览|文化|剧场|博物馆")) {
                final String kw = keyword;
                futures.add(CompletableFuture.supplyAsync(
                    () -> safeSearch(planId, intent, PoiType.CULTURE, kw, anchor), executor));
            }
            for (String keyword : keywords(intent, "diningKeywords", diningKeyword(intent))) {
                final String kw = keyword;
                futures.add(CompletableFuture.supplyAsync(
                    () -> safeSearch(planId, intent, PoiType.DINING, kw, anchor), executor));
            }
            for (String keyword : keywords(intent, "extraKeywords", "公园|书店|咖啡")) {
                final String kw = keyword;
                futures.add(CompletableFuture.supplyAsync(
                    () -> safeSearch(planId, intent, PoiType.EXTRA, kw, anchor), executor));
            }

            // 等待所有并行任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Poi> pois = futures.stream()
                    .map(f -> f.getNow(List.of()))
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

            // 若 ENTERTAINMENT 为空，补充 fallback
            if (pois.stream().noneMatch(poi -> poi.getType() == PoiType.ENTERTAINMENT)) {
                pois.addAll(searchByKeyword(planId, intent, PoiType.ENTERTAINMENT,
                        fallbackActivityKeyword(intent), anchor));
            }

            List<Poi> deduped = dedupePois(pois);
            if (deduped.size() < MIN_DEDUPED_SIZE) {
                return blockOrMock(planId, intent, "empty_or_too_few_results", SEARCH_PATH);
            }
            return deduped;
        } catch (PlanBlockedException e) {
            throw e;
        } catch (Exception e) {
            return blockOrMock(planId, intent, e.getClass().getSimpleName() + ": " + e.getMessage(), SEARCH_PATH);
        }
    }

    public Map<String, Object> nearbyPois(NearbyPoiRequest request) {
        if (request == null || request.lng() == null || request.lat() == null) {
            throw new IllegalArgumentException("location coordinates are required");
        }
        double lng = request.lng();
        double lat = request.lat();
        if (lng < 73.0 || lng > 136.0 || lat < 3.0 || lat > 54.0) {
            throw new IllegalArgumentException("location coordinates are outside supported range");
        }
        ExternalClientProperties.Amap amap = properties.getAmap();
        if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
            throw new PlanBlockedException(null, "amap", "Amap POI service is not configured", 503);
        }
        int radius = clamp(request.radius() == null ? 3000 : request.radius(), 500, 8000);
        List<NearbyPoiCategoryRequest> categories = request.categories() == null
                ? List.of()
                : request.categories();
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("at least one category is required");
        }
        Map<String, Object> grouped = new LinkedHashMap<>();
        for (NearbyPoiCategoryRequest category : categories.stream().limit(8).toList()) {
            String key = safeCategoryKey(category.key());
            String keyword = category.keyword() == null ? "" : category.keyword().trim();
            if (key.isBlank() || keyword.isBlank()) {
                continue;
            }
            grouped.put(key, searchNearbyCategory(amap, lng, lat, radius, category));
        }
        return Map.of(
                "provider", "amap",
                "mode", "real",
                "anchor", Map.of("lng", lng, "lat", lat, "radius", radius),
                "categories", grouped
        );
    }

    private List<Map<String, Object>> searchNearbyCategory(ExternalClientProperties.Amap amap, double lng, double lat,
                                                            int radius, NearbyPoiCategoryRequest category) {
        String sourceUrl = amap.getBaseUrl() + AROUND_PATH;
        int limit = clamp(category.limit() == null ? 4 : category.limit(), 1, 8);
        URI uri = URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                + "&keywords=" + encode(category.keyword())
                + "&location=" + encode(lng + "," + lat)
                + "&radius=" + radius
                + "&sortrule=distance&offset=" + Math.max(limit, 8)
                + "&page=1&extensions=base");
        try {
            Map<String, Object> body = sendAmapGet(uri, requestTimeoutMs(amap));
            return castList(body.get("pois")).stream()
                    .map(raw -> nearbyPoi(raw, category))
                    .filter(poi -> hasVisibleName(String.valueOf(poi.getOrDefault("name", ""))))
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> nearbyPoi(Map<String, Object> raw, NearbyPoiCategoryRequest category) {
        double[] point = parseLocation(String.valueOf(raw.getOrDefault("location", "0,0")));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", String.valueOf(raw.getOrDefault("name", "")));
        item.put("address", String.valueOf(raw.getOrDefault("address", "")));
        item.put("distanceMeters", parseInt(raw.get("distance")));
        item.put("lng", point[0]);
        item.put("lat", point[1]);
        item.put("categoryKey", safeCategoryKey(category.key()));
        item.put("categoryLabel", category.label() == null ? "" : category.label());
        item.put("keyword", category.keyword());
        return item;
    }

    private List<Poi> safeSearch(UUID planId, Map<String, Object> intent, PoiType type, String keyword, Anchor anchor) {
        try {
            return searchByKeyword(planId, intent, type, keyword, anchor);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Poi> searchByKeyword(UUID planId, Map<String, Object> intent, String keyword, PoiType type) throws Exception {
        return searchByKeyword(planId, intent, type, keyword, null);
    }

    private List<Poi> searchByKeyword(UUID planId, Map<String, Object> intent, PoiType type, String keyword,
                                      Anchor anchor) throws Exception {
        long start = System.currentTimeMillis();
        ExternalClientProperties.Amap amap = properties.getAmap();
        String sourceUrl = amap.getBaseUrl() + (anchor == null ? SEARCH_PATH : AROUND_PATH);
        String city = city(intent);
        URI uri = anchor == null
                ? URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                + "&keywords=" + encode(keyword)
                + cityQuery(city)
                + "&offset=" + POI_OFFSET + "&page=1&extensions=base")
                : URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                + "&keywords=" + encode(keyword)
                + "&location=" + encode(anchor.lng() + "," + anchor.lat())
                + "&radius=" + AROUND_RADIUS + "&sortrule=distance&offset=" + POI_OFFSET + "&page=1&extensions=base");

        Map<String, Object> body = Map.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            body = sendAmapGet(uri, requestTimeoutMs(amap));
            if (!isQpsLimited(body)) {
                break;
            }
            requestLimiter.backoff(attempt);
        }
        List<Map<String, Object>> rawPois = castList(body.get("pois"));
        List<Poi> mapped = rawPois.stream()
                .map(raw -> ExternalPoiMapper.fromAmap(raw, type))
                .map(poi -> normalizeExplicitNamedPoi(poi, keyword))
                .filter(poi -> poi.getLng() != 0.0 && poi.getLat() != 0.0 && hasVisibleName(poi.getName()))
                .limit(10)
                .toList();

        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", "real", sourceUrl, String.valueOf(body.getOrDefault("infocode", body.getOrDefault("httpStatus", "")))));
        output.put("keyword", keyword);
        output.put("anchor", anchor == null ? "" : anchor.name());
        output.put("count", mapped.size());
        output.put("info", body.getOrDefault("info", ""));
        output.put("apiStatus", body.getOrDefault("status", ""));
        output.put("searchStrategy", intent.getOrDefault("poiSearchStrategy", Map.of()));
        traceService.trace(planId, "AmapPoiSearchTool", mapped.isEmpty() ? "empty" : "ok", start,
                Map.of("keyword", keyword, "city", city, "type", type.name()), output);
        return mapped;
    }

    private Anchor resolveAnchor(UUID planId, Map<String, Object> intent) throws Exception {
        Map<String, Object> location = castMap(intent.get("location"));
        Object lng = location.get("lng");
        Object lat = location.get("lat");
        if (lng instanceof Number lngNumber && lat instanceof Number latNumber) {
            return new Anchor(String.valueOf(location.getOrDefault("district", "用户位置")),
                    lngNumber.doubleValue(), latNumber.doubleValue());
        }
        String district = String.valueOf(location.getOrDefault("district", ""));
        String city = city(intent);
        if (isBlank(district) || "null".equals(district)) {
            return null;
        }
        long start = System.currentTimeMillis();
        ExternalClientProperties.Amap amap = properties.getAmap();
        String sourceUrl = amap.getBaseUrl() + GEOCODE_PATH;
        URI uri = URI.create(sourceUrl + "?key=" + encode(amap.getWebServiceKey())
                + "&address=" + encode(district)
                + cityQuery(city));
        requestLimiter.awaitSlot();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(requestTimeoutMs(amap)))
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } finally {
            requestLimiter.releaseSlot();
        }
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        List<Map<String, Object>> geocodes = castList(body.get("geocodes"));
        Map<String, Object> first = geocodes.isEmpty() ? Map.of() : geocodes.get(0);
        double[] point = parseLocation(String.valueOf(first.getOrDefault("location", "0,0")));
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", "real", sourceUrl, String.valueOf(body.getOrDefault("infocode", response.statusCode()))));
        output.put("address", district);
        output.put("count", geocodes.size());
        output.put("location", first.getOrDefault("location", ""));
        output.put("info", body.getOrDefault("info", ""));
        traceService.trace(planId, "AmapGeocodeTool", point[0] == 0.0 ? "empty" : "ok", start,
                Map.of("address", district), output);
        if (point[0] == 0.0 || point[1] == 0.0) {
            throw new PlanBlockedException(planId, "amap",
                    "抱歉，暂时无法定位你填写的地点，请换成更具体的城市、商圈、地标或地址。", 422);
        }
        location.put("lng", point[0]);
        location.put("lat", point[1]);
        intent.put("location", location);
        return new Anchor(district, point[0], point[1]);
    }

    private List<Poi> blockOrMock(UUID planId, Map<String, Object> intent, String reason, String sourceUrl) {
        long start = System.currentTimeMillis();
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta(
                "amap", allowMockPoi ? "mock" : "blocked", sourceUrl == null ? "not-called" : sourceUrl, reason));
        output.put("reason", reason);
        output.put("message", "empty_or_too_few_results".equals(reason) ? BlockMessages.NO_POI_FOUND : BlockMessages.AMAP_FAILED);
        output.put("searchStrategy", intent.getOrDefault("poiSearchStrategy", Map.of()));
        traceService.trace(planId, "AmapPoiSearchTool", allowMockPoi ? "mock" : "blocked", start, intent, output);
        if (allowMockPoi) {
            return mockTools.searchPois(planId, intent);
        }
        String message = "empty_or_too_few_results".equals(reason) ? BlockMessages.NO_POI_FOUND : BlockMessages.AMAP_FAILED;
        int status = "empty_or_too_few_results".equals(reason) ? 422 : 503;
        throw new PlanBlockedException(planId, "amap", message, status);
    }

    private List<String> keywords(Map<String, Object> intent, String key, String fallback) {
        Map<String, Object> strategy = castMap(intent.get("poiSearchStrategy"));
        List<String> values = new ArrayList<>();
        Object raw = strategy.get(key);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String text = String.valueOf(item).trim();
                if (!text.isBlank() && !values.contains(text)) {
                    values.add(text);
                }
            }
        }
        if (values.isEmpty()) {
            values.add(fallback);
        }
        return values.stream().limit(MAX_KEYWORDS_PER_TYPE).toList();
    }

    private List<Poi> dedupePois(List<Poi> pois) {
        Map<String, Poi> deduped = new LinkedHashMap<>();
        for (Poi poi : pois) {
            String key = poi.getName() + "|" + poi.getAddress();
            deduped.putIfAbsent(key, poi);
        }
        return new ArrayList<>(deduped.values());
    }

    private Poi normalizeExplicitNamedPoi(Poi poi, String keyword) {
        String term = keyword == null ? "" : keyword.trim();
        if (!isExplicitPlaceKeyword(term)) {
            return poi;
        }
        String poiText = poi.getName() + " " + poi.getAddress();
        if (!poiText.contains(term) && overlapScore(poiText, term) < Math.min(4, term.length())) {
            return poi;
        }
        if (poi.getName().contains(term)) {
            return poi;
        }
        return new Poi(
                term,
                poi.getType(),
                poi.getSubtype(),
                poi.getAddress(),
                poi.getLng(),
                poi.getLat(),
                poi.getDurationMinutes(),
                poi.getAvgPrice(),
                poi.getRating(),
                poi.isKidFriendly(),
                poi.isLowCalorie(),
                poi.isIndoor(),
                poi.isSocial(),
                poi.isTicketProblem(),
                poi.isSeatProblem()
        );
    }

    private boolean isExplicitPlaceKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        if (List.of("咖啡", "海鲜", "杭帮菜", "北京烤鸭", "小杨生煎", "老北京酸奶", "龙井").contains(keyword)) {
            return false;
        }
        return keyword.length() >= 4
                || List.of("故宫", "苏堤", "断桥", "岳王庙", "曲院风荷", "人民公园").contains(keyword);
    }

    private int overlapScore(String poiText, String term) {
        int score = 0;
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (!Character.isWhitespace(ch) && poiText.indexOf(ch) >= 0) {
                score++;
            }
        }
        return score;
    }

    private String activityKeyword(Map<String, Object> intent) {
        if ("friends".equals(intent.get("scenario"))) {
            return "KTV|密室|桌游";
        }
        if ("couple".equals(intent.get("scenario"))) {
            return "展览|咖啡|夜景|艺术馆";
        }
        return "亲子|乐园|博物馆";
    }

    private String fallbackActivityKeyword(Map<String, Object> intent) {
        if ("friends".equals(intent.get("scenario"))) {
            return "娱乐|KTV";
        }
        if ("couple".equals(intent.get("scenario"))) {
            return "艺术馆|展览";
        }
        return "儿童乐园|科技馆";
    }

    private String diningKeyword(Map<String, Object> intent) {
        String constraints = String.valueOf(intent.getOrDefault("hard_constraints", List.of()));
        if (constraints.contains("饮食限制") || constraints.contains("低卡优先") || constraints.contains("低卡") || constraints.contains("减肥")) {
            return "轻食|健康餐|沙拉";
        }
        if ("friends".equals(intent.get("scenario"))) {
            return "聚餐|烧烤|餐厅";
        }
        if ("couple".equals(intent.get("scenario"))) {
            return "约会餐厅|西餐|日料";
        }
        return "亲子|餐厅";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
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

    private Map<String, Object> sendAmapGet(URI uri, int timeoutMs) throws Exception {
        requestLimiter.awaitSlot();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            body.putIfAbsent("httpStatus", response.statusCode());
            return body;
        } finally {
            requestLimiter.releaseSlot();
        }
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

    private String safeCategoryKey(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int requestTimeoutMs(ExternalClientProperties.Amap amap) {
        return Math.min(amap.getTimeoutMs(), MAX_REQUEST_TIMEOUT_MS);
    }

    private boolean hasVisibleName(String value) {
        return value != null && !value.isBlank();
    }

    private String city(Map<String, Object> intent) {
        Object city = castMap(intent.get("location")).get("city");
        String text = city == null ? "" : String.valueOf(city);
        return text.isBlank() || "null".equals(text) ? "" : text;
    }

    private String cityQuery(String city) {
        return isBlank(city) ? "" : "&city=" + encode(city);
    }

    private double[] parseLocation(String location) {
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return new double[] {0.0, 0.0};
        }
        try {
            return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException e) {
            return new double[] {0.0, 0.0};
        }
    }

    private record Anchor(String name, double lng, double lat) {}
}
