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
        List<Poi> pois = scopedMockPois(intent);
        Map<String, Object> output = new LinkedHashMap<>(traceService.externalMeta("mock", "mock", "test-mock-data", "ok"));
        output.put("count", pois.size());
        output.put("source", "test_profile_mock_poi");
        output.put("city", city(intent));
        trace(planId, "PoiSearchTool", "ok", start, intent, output);
        return pois;
    }

    private List<Poi> scopedMockPois(Map<String, Object> intent) {
        String city = city(intent);
        if (city.isBlank() || "大连".equals(city)) {
            return poiRepository.findAll();
        }
        String district = district(intent);
        double[] base = basePoint(city, district);
        String prefix = district.isBlank() || "null".equals(district) ? city : district;
        return List.of(
                new Poi(prefix + "文化展厅", PoiType.CULTURE, "城市文化展览", prefix + "核心区",
                        base[0], base[1], 80, 60, 4.7, true, false, true, true, false, false,
                        "city-scoped-mock", city + "-culture-1"),
                new Poi(prefix + "室内活动馆", PoiType.ENTERTAINMENT, "室内娱乐", prefix + "商圈",
                        base[0] + 0.006, base[1] + 0.004, 90, 90, 4.6, true, false, true, true, false, false,
                        "city-scoped-mock", city + "-entertainment-1"),
                new Poi(prefix + "轻食餐厅", PoiType.DINING, "轻食餐厅", prefix + "步行街",
                        base[0] + 0.004, base[1] - 0.003, 65, 85, 4.6, true, true, true, true, false, false,
                        "city-scoped-mock", city + "-dining-1"),
                new Poi(prefix + "本地小馆", PoiType.DINING, "本地餐厅", prefix + "街区",
                        base[0] - 0.005, base[1] + 0.003, 70, 110, 4.5, true, false, true, true, false, false,
                        "city-scoped-mock", city + "-dining-2"),
                new Poi(prefix + "咖啡店", PoiType.EXTRA, "咖啡", prefix + "转角",
                        base[0] + 0.002, base[1] + 0.006, 35, 35, 4.5, true, false, true, true, false, false,
                        "city-scoped-mock", city + "-extra-1"),
                new Poi(prefix + "公园步道", PoiType.EXTRA, "轻松散步", prefix + "附近",
                        base[0] - 0.004, base[1] - 0.004, 45, 0, 4.4, true, true, false, true, false, false,
                        "city-scoped-mock", city + "-extra-2")
        );
    }

    private String city(Map<String, Object> intent) {
        Map<String, Object> location = castMap(intent.get("location"));
        String city = String.valueOf(location.getOrDefault("city", "")).trim();
        if (!city.isBlank() && !"null".equals(city)) {
            return city;
        }
        Object signals = intent.get("citySignals");
        if (signals instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0)).trim();
        }
        return "";
    }

    private String district(Map<String, Object> intent) {
        Map<String, Object> location = castMap(intent.get("location"));
        String district = String.valueOf(location.getOrDefault("district", "")).trim();
        return "null".equals(district) ? "" : district;
    }

    private double[] basePoint(String city, String district) {
        if (district.contains("静安寺")) return new double[] {121.445, 31.224};
        if (district.contains("人民广场")) return new double[] {121.475, 31.232};
        if (district.contains("三里屯")) return new double[] {116.454, 39.934};
        if (district.contains("天安门")) return new double[] {116.397, 39.908};
        if (district.contains("西湖")) return new double[] {120.148, 30.259};
        if (district.contains("春熙路")) return new double[] {104.080, 30.657};
        if (district.contains("南山")) return new double[] {113.930, 22.533};
        if (district.contains("南屏街")) return new double[] {102.713, 25.042};
        return switch (city) {
            case "上海" -> new double[] {121.475, 31.232};
            case "北京" -> new double[] {116.407, 39.904};
            case "杭州" -> new double[] {120.155, 30.274};
            case "成都" -> new double[] {104.066, 30.572};
            case "深圳" -> new double[] {114.057, 22.543};
            case "广州" -> new double[] {113.264, 23.129};
            case "武汉" -> new double[] {114.305, 30.593};
            case "昆明" -> new double[] {102.713, 25.042};
            case "南京" -> new double[] {118.796, 32.060};
            case "西安" -> new double[] {108.940, 34.341};
            case "长沙" -> new double[] {112.939, 28.228};
            case "重庆" -> new double[] {106.551, 29.563};
            case "苏州" -> new double[] {120.585, 31.299};
            default -> new double[] {121.475, 31.232};
        };
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> executePlan(UUID planId, Map<String, Object> option) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> timeline = option.get("timeline") instanceof List<?> items
                ? (List<Map<String, Object>>) items
                : List.of();
        List<Map<String, Object>> orders = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> incidents = new ArrayList<>();
        List<Map<String, Object>> fallbackOptions = new ArrayList<>();

        Map<String, Object> multiOrigin = castMap(option.get("multiOrigin"));
        if (!multiOrigin.isEmpty()) {
            steps.add(Map.of(
                    "name", "多起点接人已安排",
                    "action", "multi_origin_pickup",
                    "status", "done",
                    "provider", "mock",
                    "mode", "mock",
                    "message", String.valueOf(multiOrigin.getOrDefault("summary", "多起点汇合接人已纳入模拟执行。"))
            ));
        }

        Map<String, Object> ride = rideOrder(planId, option, timeline);
        orders.add(ride);
        steps.add(step("打车已模拟叫车", ride, "从出发地到首个地点，费用和车牌均为 Mock 演示。"));

        for (Map<String, Object> item : timeline) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            if ("餐饮".equals(type) || "dining".equalsIgnoreCase(type)) {
                Map<String, Object> reservation = reservationOrder(planId, item);
                orders.add(reservation);
                steps.add(step("餐厅订座已处理", reservation, String.valueOf(reservation.get("message"))));
                if ("fallback_confirmed".equals(reservation.get("status"))) {
                    Map<String, Object> incident = incident("NO_SEAT", item, reservation, "原餐厅满员，已模拟改订同区域相似餐厅。");
                    incidents.add(incident);
                    fallbackOptions.add(fallback(incident));
                }
            } else if ("活动".equals(type) || "activity".equalsIgnoreCase(type)
                    || "娱乐".equals(type) || "文化".equals(type)) {
                Map<String, Object> ticket = ticketOrder(planId, item);
                orders.add(ticket);
                steps.add(step("活动购票已处理", ticket, String.valueOf(ticket.get("message"))));
                if ("fallback_confirmed".equals(ticket.get("status"))) {
                    Map<String, Object> incident = incident("NO_TICKET", item, ticket, "原活动无票，已模拟替换为同类型可入场活动。");
                    incidents.add(incident);
                    fallbackOptions.add(fallback(incident));
                }
            }
        }

        Map<String, Object> gift = delivery(planId, option);
        orders.add(deliveryOrder(gift));
        steps.add(step("配送提醒已安排", deliveryOrder(gift), "送达时间为 Mock 估算，出发前可再次确认。"));
        for (Map<String, Object> recovery : castList(option.get("constraintRecoveries"))) {
            Map<String, Object> incident = recoveryIncident(recovery);
            incidents.add(incident);
            fallbackOptions.add(fallback(incident));
            steps.add(Map.of(
                    "name", "行程冲突已处理",
                    "action", "time_conflict_recovery",
                    "status", "warn",
                    "provider", "mock",
                    "mode", "mock",
                    "message", String.valueOf(incident.get("reason"))
            ));
        }
        String shareMessage = share(planId, option);
        steps.add(Map.of(
                "name", "分享消息已生成",
                "action", "share_message",
                "status", "done",
                "provider", "mock",
                "mode", "mock",
                "message", shareMessage
        ));

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("mode", "mock");
        execution.put("provider", "local-mock");
        execution.put("orders", orders);
        execution.put("gift", gift);
        execution.put("shareMessage", shareMessage);
        execution.put("executionSteps", steps);
        execution.put("incidents", incidents);
        execution.put("fallbackOptions", fallbackOptions);
        execution.put("allSuccess", incidents.stream().noneMatch(incident -> "BLOCKED".equals(incident.get("status"))));
        execution.put("selectedRank", option.get("rank"));
        trace(planId, "ExecutionCenterTool", "ok", start,
                Map.of("rank", option.get("rank")),
                Map.of("orders", orders.size(), "incidents", incidents.size(), "mode", "mock"));
        return execution;
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
        String message = "搞定啦！本次为模拟执行：" + startTime + "出发，打车先去" + option.get("firstStop") + "，然后去"
                + option.get("diningName") + "吃饭，最后在" + option.get("lastStop")
                + "收尾。打车、购票、订座和配送都已按 Mock 流程安排好。";
        trace(planId, "ShareMessageTool", "ok", start, Map.of("rank", option.get("rank")), Map.of("message", message));
        return message;
    }

    private Map<String, Object> rideOrder(UUID planId, Map<String, Object> option, List<Map<String, Object>> timeline) {
        String orderNo = "模拟单-" + UUID.randomUUID().toString().substring(0, 8);
        String firstStop = timeline.isEmpty()
                ? String.valueOf(option.getOrDefault("firstStop", "首个地点"))
                : String.valueOf(timeline.get(0).getOrDefault("name", "首个地点"));
        mockOrderRepository.save(new MockOrder(planId, orderNo, "打车", firstStop, "已派单"));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderNo", orderNo);
        order.put("action", "ride_hailing");
        order.put("actionLabel", "打车");
        order.put("target", firstStop);
        order.put("targetName", firstStop);
        order.put("status", "confirmed");
        order.put("statusLabel", "已派单");
        order.put("provider", "mock");
        order.put("mode", "mock");
        order.put("vehicle", "沪A-Mock8");
        order.put("driver", "模拟司机");
        order.put("estimatedFare", 36);
        order.put("message", "已模拟叫车到首个目的地");
        return order;
    }

    private Map<String, Object> reservationOrder(UUID planId, Map<String, Object> item) {
        String name = String.valueOf(item.getOrDefault("name", "餐厅"));
        boolean full = name.contains("家庭海鲜") || name.contains("满") || name.contains("排队");
        String target = full ? replacementName(name, "同区域家庭餐厅") : name;
        String orderNo = "模拟单-" + UUID.randomUUID().toString().substring(0, 8);
        String status = full ? "fallback_confirmed" : "confirmed";
        mockOrderRepository.save(new MockOrder(planId, orderNo, "订座", target, full ? "已替换并确认" : "已确认"));
        Map<String, Object> order = baseOrder(orderNo, "restaurant_reservation", "订座", target, status);
        order.put("originalTarget", full ? name : "");
        order.put("people", 4);
        order.put("reservationTime", String.valueOf(item.getOrDefault("time", "")));
        order.put("message", full ? "原餐厅满员，已模拟改订同区域相似餐厅。" : "餐厅座位已模拟确认。");
        return order;
    }

    private Map<String, Object> ticketOrder(UUID planId, Map<String, Object> item) {
        String name = String.valueOf(item.getOrDefault("name", "活动"));
        boolean soldOut = name.contains("科学剧场") || name.contains("艺术馆") || name.contains("无票");
        String target = soldOut ? replacementName(name, "同类型室内活动") : name;
        String orderNo = "模拟单-" + UUID.randomUUID().toString().substring(0, 8);
        String status = soldOut ? "fallback_confirmed" : "confirmed";
        mockOrderRepository.save(new MockOrder(planId, orderNo, "购票", target, soldOut ? "已替换并确认" : "已确认"));
        Map<String, Object> order = baseOrder(orderNo, "ticket_booking", "购票", target, status);
        order.put("originalTarget", soldOut ? name : "");
        order.put("ticketCount", 4);
        order.put("entryTime", String.valueOf(item.getOrDefault("time", "")));
        order.put("pickupCode", "MOCK-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        order.put("message", soldOut ? "原活动无票，已模拟替换为同类型可入场活动。" : "活动门票已模拟确认。");
        return order;
    }

    private Map<String, Object> deliveryOrder(Map<String, Object> gift) {
        Map<String, Object> order = baseOrder(
                String.valueOf(gift.getOrDefault("orderNo", "模拟单-" + UUID.randomUUID().toString().substring(0, 8))),
                "delivery",
                "配送",
                String.valueOf(gift.getOrDefault("target", gift.getOrDefault("targetName", "餐厅"))),
                "confirmed"
        );
        order.put("eta", String.valueOf(gift.getOrDefault("eta", "")));
        order.put("message", "配送提醒已模拟安排。");
        return order;
    }

    private Map<String, Object> baseOrder(String orderNo, String action, String actionLabel, String target, String status) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderNo", orderNo);
        order.put("action", action);
        order.put("actionLabel", actionLabel);
        order.put("target", target);
        order.put("targetName", target);
        order.put("status", status);
        order.put("statusLabel", "fallback_confirmed".equals(status) ? "已替换并确认" : "已确认");
        order.put("provider", "mock");
        order.put("mode", "mock");
        return order;
    }

    private Map<String, Object> step(String name, Map<String, Object> order, String message) {
        return Map.of(
                "name", name,
                "action", String.valueOf(order.getOrDefault("action", "")),
                "status", "fallback_confirmed".equals(order.get("status")) ? "warn" : "done",
                "provider", "mock",
                "mode", "mock",
                "orderNo", String.valueOf(order.getOrDefault("orderNo", "")),
                "message", message
        );
    }

    private Map<String, Object> incident(String code, Map<String, Object> item, Map<String, Object> order, String message) {
        return Map.of(
                "code", code,
                "status", "RECOVERED",
                "originalTarget", String.valueOf(item.getOrDefault("name", "")),
                "fallbackTarget", String.valueOf(order.getOrDefault("targetName", "")),
                "reason", message,
                "provider", "mock",
                "mode", "mock"
        );
    }

    private Map<String, Object> recoveryIncident(Map<String, Object> recovery) {
        return Map.of(
                "code", String.valueOf(recovery.getOrDefault("code", "TIME_CONFLICT")),
                "status", String.valueOf(recovery.getOrDefault("status", "RECOVERED")),
                "originalTarget", "原始时间安排",
                "fallbackTarget", String.valueOf(recovery.getOrDefault("fallbackTarget", "压缩停留时间")),
                "reason", String.valueOf(recovery.getOrDefault("reason", "已调整行程节奏，优先满足硬结束时间。")),
                "provider", "mock",
                "mode", "mock"
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private Map<String, Object> fallback(Map<String, Object> incident) {
        return Map.of(
                "originalTarget", String.valueOf(incident.getOrDefault("originalTarget", "")),
                "fallbackTarget", String.valueOf(incident.getOrDefault("fallbackTarget", "")),
                "reason", String.valueOf(incident.getOrDefault("reason", "")),
                "source", "mock"
        );
    }

    private String replacementName(String original, String suffix) {
        return original + "替代-" + suffix;
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
