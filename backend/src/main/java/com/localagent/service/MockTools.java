package com.localagent.service;

import com.localagent.model.MockOrder;
import com.localagent.model.Poi;
import com.localagent.model.PoiType;
import com.localagent.repo.MockOrderRepository;
import com.localagent.repo.PoiRepository;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MockTools {
    private final PoiRepository poiRepository;
    private final MockOrderRepository mockOrderRepository;
    private final ToolTraceService traceService;

    public MockTools(PoiRepository poiRepository, MockOrderRepository mockOrderRepository, ToolTraceService traceService) {
        this.poiRepository = poiRepository;
        this.mockOrderRepository = mockOrderRepository;
        this.traceService = traceService;
    }

    public List<Poi> searchPois(UUID planId, Map<String, Object> intent) {
        long start = System.currentTimeMillis();
        List<Poi> pois = poiRepository.findAll();
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta("mock", "mock", "test-mock-data", "ok"));
        output.put("count", pois.size());
        output.put("source", "test_profile_mock_poi");
        trace(planId, "PoiSearchTool", "ok", start, intent, output);
        return pois;
    }

    public Map<String, Object> route(UUID planId, List<Poi> stops) {
        long start = System.currentTimeMillis();
        int totalSeconds = 0;
        double distance = 0.0;
        List<Integer> segmentMinutes = new ArrayList<>();
        List<Double> segmentDistancesKm = new ArrayList<>();
        List<Map<String, Object>> segments = new ArrayList<>();
        List<String> routeModes = new ArrayList<>();
        for (int i = 1; i < stops.size(); i++) {
            Poi from = stops.get(i - 1);
            Poi to = stops.get(i);
            double segment = distanceKm(from, to);
            double roundedSegment = Math.round(segment * 10.0) / 10.0;
            distance += segment;
            String mode = routeMode(segment);
            int secondsPerKm = switch (mode) {
                case "walking" -> 720;
                case "driving" -> 120;
                default -> 240;
            };
            int segSeconds = Math.max(60, (int) Math.round(segment * secondsPerKm));
            totalSeconds += segSeconds;
            int minutes = Math.max(1, (int) Math.ceil(segSeconds / 60.0));
            segmentMinutes.add(minutes);
            segmentDistancesKm.add(roundedSegment);
            routeModes.add(mode);
            segments.add(Map.of(
                    "from", from.getName(),
                    "to", to.getName(),
                    "durationMinutes", minutes,
                    "distanceKm", roundedSegment,
                    "routeMode", mode
            ));
        }
        int travel = totalSeconds <= 0 ? 0 : Math.max(1, (int) Math.ceil(totalSeconds / 60.0));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("travelMinutes", travel);
        output.put("distanceKm", Math.round(distance * 10.0) / 10.0);
        output.put("segmentMinutes", segmentMinutes);
        output.put("segmentDistancesKm", segmentDistancesKm);
        output.put("segments", segments);
        output.put("routeModes", routeModes);
        output.put("source", "mock_dynamic_route_estimate");
        output.putAll(traceService.externalMeta("mock", "mock", "local-distance", "ok"));
        trace(planId, "RouteEstimateTool", "ok", start, Map.of("stops", stops.stream().map(Poi::getName).toList()), output);
        return output;
    }

    public boolean hasSeat(UUID planId, Poi restaurant) {
        long start = System.currentTimeMillis();
        boolean ok = !restaurant.isSeatProblem();
        trace(planId, "RestaurantAvailabilityTool", ok ? "ok" : "no_seat", start,
                Map.of("restaurant", restaurant.getName()),
                Map.of("available", ok, "queueMinutes", ok ? 0 : 55));
        return ok;
    }

    public boolean hasTicket(UUID planId, Poi activity) {
        long start = System.currentTimeMillis();
        boolean ok = !activity.isTicketProblem();
        trace(planId, "TicketAvailabilityTool", ok ? "ok" : "no_ticket", start,
                Map.of("activity", activity.getName()),
                Map.of("available", ok, "remaining", ok ? 20 : 0));
        return ok;
    }

    public List<Map<String, Object>> book(UUID planId, Map<String, Object> option) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> orders = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) option.get("timeline");
        if (timeline == null) {
            trace(planId, "BookingTool", "ok", start, Map.of("rank", option.get("rank")), Map.of("orders", orders));
            return orders;
        }
        for (Map<String, Object> item : timeline) {
            String type = String.valueOf(item.get("type"));
            String name = String.valueOf(item.get("name"));
            if ("餐饮".equals(type) || "活动".equals(type) || "dining".equals(type) || "activity".equals(type)) {
                String action = ("餐饮".equals(type) || "dining".equals(type)) ? "订座" : "购票";
                String orderNo = "模拟单-" + UUID.randomUUID().toString().substring(0, 8);
                mockOrderRepository.save(new MockOrder(planId, orderNo, action, name, "已确认"));
                orders.add(Map.of("orderNo", orderNo, "action", action, "target", name, "status", "已确认"));
            }
        }
        trace(planId, "BookingTool", "ok", start, Map.of("rank", option.get("rank")), Map.of("orders", orders));
        return orders;
    }

    public Map<String, Object> delivery(UUID planId, Map<String, Object> option) {
        long start = System.currentTimeMillis();
        String orderNo = "模拟单-" + UUID.randomUUID().toString().substring(0, 8);
        String target = String.valueOf(option.get("diningName"));
        mockOrderRepository.save(new MockOrder(planId, orderNo, "配送", target, "已安排"));
        Map<String, Object> output = Map.of("orderNo", orderNo, "target", target, "status", "已安排", "eta", "18:10");
        trace(planId, "DeliveryGiftTool", "ok", start, Map.of("target", target), output);
        return output;
    }

    public String share(UUID planId, Map<String, Object> option) {
        long start = System.currentTimeMillis();
        String startTime = extractStartTime(option);
        String message = "搞定啦！" + startTime + "出发，先去" + option.get("firstStop") + "，然后去"
                + option.get("diningName") + "吃饭，最后在" + option.get("lastStop")
                + "收尾。所有预订都已安排好。";
        trace(planId, "ShareMessageTool", "ok", start, Map.of("rank", option.get("rank")), Map.of("message", message));
        return message;
    }

    @SuppressWarnings("unchecked")
    private String extractStartTime(Map<String, Object> option) {
        try {
            List<Map<String, Object>> timeline = (List<Map<String, Object>>) option.get("timeline");
            if (timeline != null && !timeline.isEmpty()) {
                String time = String.valueOf(timeline.get(0).getOrDefault("time", ""));
                if (!time.isBlank() && !"null".equals(time) && !"--:--".equals(time)) {
                    return time;
                }
            }
        } catch (Exception ignored) {}
        return "出发时间";
    }

    public void recovery(UUID planId, String reason, String from, String to) {
        long start = System.currentTimeMillis();
        trace(planId, "ExceptionRecoveryTool", "recovered", start,
                Map.of("reason", reason, "from", from),
                Map.of("replacement", to, "policy", "优先保护硬约束"));
    }

    public List<Poi> sortCandidates(List<Poi> pois, Map<String, Object> intent) {
        boolean family = "family".equals(intent.get("scenario"));
        boolean lowCal = ((List<?>) intent.getOrDefault("hard_constraints", List.of())).contains("低卡优先");
        List<String> explicitTerms = explicitPoiTerms(intent);
        return pois.stream()
                .sorted(Comparator.comparing((Poi poi) -> scorePoi(poi, family, lowCal, explicitTerms)).reversed())
                .toList();
    }

    public int totalMinutes(List<Poi> stops, int travelMinutes) {
        return stops.stream().mapToInt(Poi::getDurationMinutes).sum() + travelMinutes;
    }

    public String startTime(int index) {
        return LocalTime.of(14, 0).plusMinutes(index * 95L).toString();
    }

    private int scorePoi(Poi poi, boolean family, boolean lowCal, List<String> explicitTerms) {
        int score = (int) Math.round(poi.getRating() * 20);
        String poiText = (poi.getName() + " " + poi.getAddress()).toLowerCase();
        for (String term : explicitTerms) {
            String normalized = term.toLowerCase();
            if (poiText.contains(normalized) || normalized.contains(poi.getName().toLowerCase())) {
                score += 45;
                break;
            }
            if (overlapScore(poiText, normalized) >= Math.min(4, normalized.length())) {
                score += 18;
            }
        }
        if (family && poi.isKidFriendly()) score += 25;
        if (!family && poi.isSocial()) score += 20;
        if (lowCal && poi.isLowCalorie()) score += 30;
        if (poi.isSeatProblem() || poi.isTicketProblem()) score -= 8;
        return score;
    }

    @SuppressWarnings("unchecked")
    private List<String> explicitPoiTerms(Map<String, Object> intent) {
        Map<String, Object> strategy = intent.get("poiSearchStrategy") instanceof Map<?, ?>
                ? (Map<String, Object>) intent.get("poiSearchStrategy")
                : Map.of();
        List<String> terms = new ArrayList<>();
        for (String key : List.of("activityKeywords", "diningKeywords", "extraKeywords")) {
            Object raw = strategy.get(key);
            if (!(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                String text = String.valueOf(item).trim();
                if (text.length() >= 4 && !isGenericKeyword(text) && !terms.contains(text)) {
                    terms.add(text);
                }
            }
        }
        return terms;
    }

    private boolean isGenericKeyword(String text) {
        return List.of("室内", "商场", "展览", "文化", "公园", "娱乐", "餐厅", "简餐", "咖啡", "书店", "海鲜", "杭帮菜", "北京烤鸭")
                .contains(text);
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

    private double distanceKm(Poi a, Poi b) {
        double dx = (a.getLng() - b.getLng()) * 85.0;
        double dy = (a.getLat() - b.getLat()) * 111.0;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private String routeMode(double distanceKm) {
        if (distanceKm <= 1.0) {
            return "walking";
        }
        if (distanceKm <= 5.0) {
            return "bicycling";
        }
        return "driving";
    }

    private void trace(UUID planId, String toolName, String status, long start, Object input, Object output) {
        traceService.trace(planId, toolName, status, start, input, output);
    }
}
