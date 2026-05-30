package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.dto.ApiDtos.PlanRequest;
import com.localagent.dto.ApiDtos.PlanResponse;
import com.localagent.model.FeedbackEvent;
import com.localagent.model.PlanOption;
import com.localagent.model.PlanSession;
import com.localagent.model.PlanStatus;
import com.localagent.model.Poi;
import com.localagent.model.PoiType;
import com.localagent.model.ToolCallLog;
import com.localagent.repo.FeedbackEventRepository;
import com.localagent.repo.PlanOptionRepository;
import com.localagent.repo.PlanSessionRepository;
import com.localagent.repo.ToolCallLogRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningService {
    private static final double MAX_POI_DISTANCE_KM = 12.0;
    private static final double MAX_ROUTE_DISTANCE_KM = 8.0;
    private static final double MAX_DIRECT_ROUTE_PREFILTER_KM = 10.5;
    private static final int MAX_ROUTE_ATTEMPTS = 8;

    private final PlanSessionRepository planSessionRepository;
    private final PlanOptionRepository planOptionRepository;
    private final FeedbackEventRepository feedbackEventRepository;
    private final ToolCallLogRepository toolCallLogRepository;
    private final MockTools tools;
    private final AmapPoiSearchTool poiSearchTool;
    private final AmapRouteEstimateTool routeEstimateTool;
    private final SearchVerifierAgent searchVerifierAgent;
    private final IntentParserAgent intentParserAgent;
    private final ClarificationService clarificationService;
    private final AmapWeatherTool weatherTool;
    private final PlanValidationService planValidationService;
    private final PromptCatalog promptCatalog;
    private final ToolTraceService toolTraceService;
    private final ObjectMapper objectMapper;
    private final boolean allowMockPoi;

    public PlanningService(PlanSessionRepository planSessionRepository,
                           PlanOptionRepository planOptionRepository,
                           FeedbackEventRepository feedbackEventRepository,
                           ToolCallLogRepository toolCallLogRepository,
                           MockTools tools,
                           AmapPoiSearchTool poiSearchTool,
                           AmapRouteEstimateTool routeEstimateTool,
                           SearchVerifierAgent searchVerifierAgent,
                           IntentParserAgent intentParserAgent,
                           ClarificationService clarificationService,
                           AmapWeatherTool weatherTool,
                           PlanValidationService planValidationService,
                           PromptCatalog promptCatalog,
                           ToolTraceService toolTraceService,
                           ObjectMapper objectMapper,
                           @Value("${app.allow-mock-poi:false}") boolean allowMockPoi) {
        this.planSessionRepository = planSessionRepository;
        this.planOptionRepository = planOptionRepository;
        this.feedbackEventRepository = feedbackEventRepository;
        this.toolCallLogRepository = toolCallLogRepository;
        this.tools = tools;
        this.poiSearchTool = poiSearchTool;
        this.routeEstimateTool = routeEstimateTool;
        this.searchVerifierAgent = searchVerifierAgent;
        this.intentParserAgent = intentParserAgent;
        this.clarificationService = clarificationService;
        this.weatherTool = weatherTool;
        this.planValidationService = planValidationService;
        this.promptCatalog = promptCatalog;
        this.toolTraceService = toolTraceService;
        this.objectMapper = objectMapper;
        this.allowMockPoi = allowMockPoi;
    }

    public PlanResponse createPlan(String token, PlanRequest request) {
        String message = request == null ? "" : request.message();
        PlanSession session = planSessionRepository.save(PlanSession.create(token, message));
        Map<String, Object> intent = intentParserAgent.parse(session.getId(), message);
        intent.put("rawMessage", message);
        clarificationService.ensureUserFacts(intent, message);
        intent = clarificationService.mergeAnswers(intent, request == null ? null : request.clarificationAnswers());
        applyRequestPreferences(intent, request);
        ensurePoiSearchStrategy(intent);
        Map<String, Object> clarification = clarificationService.buildClarification(session.getId(), intent, message);
        if (!clarification.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("options", List.of());
            result.put("clarification", clarification);
            result.put("warnings", List.of("信息补齐前不会查询真实地点，也不会生成方案。"));
            session.markNeedsClarification(toJson(intent), toJson(result));
            planSessionRepository.save(session);
            return toResponse(session.getId());
        }
        try {
            Map<String, Object> weather = weatherTool.weather(session.getId(), intent);
            List<Map<String, Object>> webEvidence = searchVerifierAgent.verify(session.getId(), intent, message);
            List<Poi> candidates = poiSearchTool.searchPois(session.getId(), intent);
            List<Map<String, Object>> warnings = new ArrayList<>(weatherWarnings(weather));
            List<Map<String, Object>> options = buildOptions(session.getId(), intent, candidates, weather, warnings);
            planValidationService.validate(session.getId(), options, candidates);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("options", options);
            result.put("weather", weather);
            result.put("warnings", warnings);
            result.put("promptModules", promptCatalog.loadPrompts().keySet());
            result.put("agentPipeline", promptCatalog.loadPipeline());
            result.put("webEvidenceCount", webEvidence.size());
            result.put("generationMode", "llm_strategy_deterministic_tools");
            session.markReady(toJson(intent), toJson(result));
            planSessionRepository.save(session);
            for (Map<String, Object> option : options) {
                planOptionRepository.save(new PlanOption(session.getId(), ((Number) option.get("rank")).intValue(), toJson(option)));
            }
            return toResponse(session.getId());
        } finally {
            routeEstimateTool.clearCache(session.getId());
        }
    }

    public PlanResponse createPlan(String token, String message) {
        return createPlan(token, new PlanRequest(message, null, null, null, null));
    }

    @Transactional(readOnly = true)
    public PlanResponse getPlan(UUID id) {
        return toResponse(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> traceFor(UUID id) {
        if (id == null) {
            return List.of();
        }
        return toolCallLogRepository.findByPlanSessionIdOrderByCreatedAt(id).stream()
                .map(this::tracePayload)
                .toList();
    }

    @Transactional
    public PlanResponse confirm(UUID id, int rank) {
        PlanSession session = planSessionRepository.findById(id).orElseThrow();
        if (session.getStatus() != PlanStatus.READY && session.getStatus() != PlanStatus.COMPLETED) {
            throw new IllegalStateException("方案尚未生成完成，暂不能确认执行");
        }
        Map<String, Object> option = findOption(id, rank);
        session.markExecuting();
        planSessionRepository.save(session);
        List<Map<String, Object>> orders = tools.book(id, option);
        Map<String, Object> gift = tools.delivery(id, option);
        String shareMessage = tools.share(id, option);
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("orders", orders);
        execution.put("gift", gift);
        execution.put("shareMessage", shareMessage);
        execution.put("allSuccess", true);
        session.markCompleted(toJson(execution));
        planSessionRepository.save(session);
        return toResponse(id);
    }

    public PlanResponse feedback(UUID id, String message) {
        PlanSession previous = planSessionRepository.findById(id).orElseThrow();
        feedbackEventRepository.save(new FeedbackEvent(id, message));
        Map<String, Object> previousIntent = fromJson(previous.getIntentJson());
        Map<String, Object> previousOption = findOptionOrEmpty(id, 1);
        Map<String, Object> patch = feedbackPatch(id, message, previousIntent, previousOption);
        String combined = previous.getRawInput() + "。用户调整：" + message;
        return createPlan(previous.getSessionToken(), new PlanRequest(combined, intValue(patch.get("requestedPlanCount")),
                stringValue(patch.get("stopCountPreference")), patch, previous.getId()));
    }

    Map<String, Object> analyzeIntent(String message) {
        return intentParserAgent.keywordFallback(message);
    }

    private void applyRequestPreferences(Map<String, Object> intent, PlanRequest request) {
        if (request == null) {
            return;
        }
        if (request.planCount() != null) {
            intent.put("requestedPlanCount", clamp(request.planCount(), 1, 5));
        }
        if (request.stopCountPreference() != null && !request.stopCountPreference().isBlank()) {
            intent.put("stopCountPreference", request.stopCountPreference());
        }
    }

    private void ensurePoiSearchStrategy(Map<String, Object> intent) {
        Map<String, Object> strategy = new LinkedHashMap<>(castMap(intent.get("poiSearchStrategy")));
        if (strategy.isEmpty()) {
            strategy.put("activityKeywords", defaultActivityKeywords(intent));
            strategy.put("diningKeywords", defaultDiningKeywords(intent));
            strategy.put("extraKeywords", List.of("咖啡", "书店", "公园"));
            strategy.put("rankingWeights", Map.of("distance", 0.35, "rating", 0.25, "budgetFit", 0.15, "scenarioFit", 0.25));
            strategy.put("butlerNotes", List.of("优先真实地点、短路线、预算匹配和同行人安全舒适度。"));
        } else {
            strategy.putIfAbsent("activityKeywords", defaultActivityKeywords(intent));
            strategy.putIfAbsent("diningKeywords", defaultDiningKeywords(intent));
            strategy.putIfAbsent("extraKeywords", List.of("咖啡", "书店", "公园"));
            strategy.putIfAbsent("rankingWeights", Map.of("distance", 0.35, "rating", 0.25, "budgetFit", 0.15, "scenarioFit", 0.25));
            strategy.putIfAbsent("butlerNotes", List.of());
        }
        intent.put("poiSearchStrategy", strategy);
    }

    private List<String> defaultActivityKeywords(Map<String, Object> intent) {
        String scenario = String.valueOf(intent.getOrDefault("scenario", "unknown"));
        List<String> hard = castStringList(intent.get("hard_constraints"));
        if ("family".equals(scenario) || hard.contains("儿童友好")) {
            return List.of("亲子", "儿童乐园", "博物馆", "科技馆");
        }
        if ("friends".equals(scenario)) {
            return List.of("桌游", "密室", "KTV", "展览");
        }
        if ("couple".equals(scenario)) {
            return List.of("展览", "咖啡", "夜景", "艺术馆");
        }
        return List.of("展览", "文化", "公园", "娱乐");
    }

    private List<String> defaultDiningKeywords(Map<String, Object> intent) {
        List<String> hard = castStringList(intent.get("hard_constraints"));
        if (hard.contains("饮食限制") || hard.contains("低卡优先")) {
            return List.of("轻食", "健康餐", "清淡餐厅", "沙拉");
        }
        String scenario = String.valueOf(intent.getOrDefault("scenario", "unknown"));
        if ("family".equals(scenario)) {
            return List.of("亲子餐厅", "儿童友好餐厅", "家庭餐厅");
        }
        if ("friends".equals(scenario)) {
            return List.of("聚餐", "烧烤", "火锅", "餐厅");
        }
        if ("couple".equals(scenario)) {
            return List.of("约会餐厅", "西餐", "日料", "清淡餐厅");
        }
        return List.of("餐厅", "简餐", "清淡餐厅");
    }

    private Map<String, Object> feedbackPatch(UUID planId, String message, Map<String, Object> previousIntent,
                                              Map<String, Object> previousOption) {
        long start = System.currentTimeMillis();
        String text = message == null ? "" : message;
        Map<String, Object> patch = new LinkedHashMap<>();
        Map<String, Object> preferences = new LinkedHashMap<>(castMap(previousIntent.get("soft_preferences")));
        List<String> hard = new ArrayList<>(castStringList(previousIntent.get("hard_constraints")));
        List<String> excluded = new ArrayList<>(castStringList(previousIntent.get("excludedPois")));

        if (text.contains("预算") || text.contains("太贵") || text.contains("便宜")) {
            preferences.put("budget", "low");
            Integer amount = extractNumber(text);
            if (amount != null) {
                preferences.put("budgetAmount", amount);
            }
        }
        if (text.contains("不要太远") || text.contains("近一点") || text.contains("少走路")) {
            if (!hard.contains("距离近")) hard.add("距离近");
            preferences.put("distance", "nearby");
        }
        if (text.contains("清淡") || text.contains("轻食") || text.contains("低卡")) {
            if (!hard.contains("低卡优先")) hard.add("低卡优先");
            preferences.put("vibe", "清淡轻松");
        }
        if (text.contains("丰富") || text.contains("多几个")) {
            patch.put("stopCountPreference", "丰富");
        }
        if (text.contains("简洁") || text.contains("少一点")) {
            patch.put("stopCountPreference", "简洁");
        }
        Integer planCount = extractPlanCount(text);
        if (planCount != null) {
            patch.put("requestedPlanCount", clamp(planCount, 1, 5));
        }
        String excludedPoi = extractExcludedPoi(text, previousOption);
        if (excludedPoi != null && !excluded.contains(excludedPoi)) {
            excluded.add(excludedPoi);
        }
        patch.put("soft_preferences", preferences);
        patch.put("hard_constraints", hard);
        patch.put("excludedPois", excluded);
        toolTraceService.trace(planId, "FeedbackIntentPatchAgent", "ok", start,
                Map.of("message", text), Map.of("provider", "local", "mode", "rule", "patch", patch));
        return patch;
    }

    private List<Map<String, Object>> buildOptions(UUID planId, Map<String, Object> intent, List<Poi> candidates,
                                                   Map<String, Object> weather, List<Map<String, Object>> warnings) {
        List<Poi> nearby = nearbyCandidates(candidates, intent);
        List<String> excludedPois = castStringList(intent.get("excludedPois"));
        nearby = nearby.stream().filter(poi -> !excludedPois.contains(poi.getName())).toList();
        List<Poi> activities = tools.sortCandidates(nearby.stream()
                .filter(poi -> poi.getType() == PoiType.ENTERTAINMENT || poi.getType() == PoiType.CULTURE)
                .toList(), intent);
        List<Poi> dining = tools.sortCandidates(nearby.stream()
                .filter(poi -> poi.getType() == PoiType.DINING)
                .toList(), intent);
        List<Poi> extras = tools.sortCandidates(nearby.stream()
                .filter(poi -> poi.getType() == PoiType.EXTRA)
                .toList(), intent);
        if (extras.isEmpty()) {
            extras = activities.stream().skip(Math.min(1, activities.size())).toList();
        }
        if (activities.isEmpty() || dining.isEmpty() || extras.isEmpty()) {
            throw new PlanBlockedException(planId, "amap", BlockMessages.NO_POI_FOUND, 422);
        }

        int planCount = planCount(intent);
        int stopCount = stopCount(intent);
        int minimumPlanCount = Math.min(3, planCount);
        List<Map<String, Object>> options = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        int maxAttempts = Math.min(MAX_ROUTE_ATTEMPTS, Math.max(minimumPlanCount, activities.size() * dining.size()));
        for (int attempt = 0; attempt < maxAttempts && options.size() < planCount; attempt++) {
            Poi activity = chooseActivity(planId, activities, attempt % activities.size());
            Poi restaurant = chooseRestaurant(planId, dining, (attempt / Math.max(1, activities.size())) % dining.size());
            List<Poi> stops = fitStopDurations(buildStops(activities, dining, extras, activity, restaurant, stopCount, attempt),
                    durationMinutes(intent));
            if (!hasRequiredPoiCoverage(stops)) {
                tools.recovery(planId, "POI类型不完整", signature(stops), "跳过该候选");
                continue;
            }
            if (directRouteDistanceKm(stops) > MAX_DIRECT_ROUTE_PREFILTER_KM) {
                tools.recovery(planId, "路线直线距离预筛过远", signature(stops), "跳过该候选");
                continue;
            }
            String signature = signature(stops);
            if (!signatures.add(signature)) {
                continue;
            }

            Map<String, Object> route = routeEstimateTool.route(planId, stops);
            double distanceKm = ((Number) route.getOrDefault("distanceKm", 0)).doubleValue();
            if (distanceKm > MAX_ROUTE_DISTANCE_KM) {
                tools.recovery(planId, "路线距离过远", signature, "跳过该候选");
                continue;
            }
            int totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
            if (fitsDuration(intent, totalMinutes)) {
                options.add(option(options.size() + 1, stops, route, totalMinutes, intent, weather));
            } else {
                tools.recovery(planId, "时间冲突", signature, "跳过该候选");
            }
        }
        if (options.isEmpty()) {
            throw new PlanBlockedException(planId, "amap",
                    "抱歉，真实地点不足以生成你要求数量的距离合理方案，请放宽地点范围、减少方案数量或稍后重试", 422);
        }
        if (options.size() < planCount) {
            warnings.add(warning("可行方案数量不足",
                    "真实地点和路线约束下仅生成 " + options.size() + " 套可行方案，少于你请求的 " + planCount + " 套。"));
        }

        List<Map<String, Object>> sorted = options.stream()
                .sorted(Comparator.comparing((Map<String, Object> it) -> ((Number) it.get("score")).doubleValue()).reversed())
                .toList();
        for (int index = 0; index < sorted.size(); index++) {
            sorted.get(index).put("rank", index + 1);
        }
        return sorted;
    }

    private boolean hasRequiredPoiCoverage(List<Poi> stops) {
        boolean hasDining = stops.stream().anyMatch(poi -> poi.getType() == PoiType.DINING);
        boolean hasActivity = stops.stream().anyMatch(poi -> poi.getType() == PoiType.ENTERTAINMENT || poi.getType() == PoiType.CULTURE);
        return stops.size() >= 3 && hasDining && hasActivity;
    }

    private double directRouteDistanceKm(List<Poi> stops) {
        double distance = 0.0;
        for (int i = 1; i < stops.size(); i++) {
            Poi from = stops.get(i - 1);
            Poi to = stops.get(i);
            distance += distanceKm(from.getLat(), from.getLng(), to.getLat(), to.getLng());
        }
        return distance;
    }

    private List<Poi> nearbyCandidates(List<Poi> candidates, Map<String, Object> intent) {
        double[] anchor = anchor(intent);
        if (anchor == null) {
            return candidates.stream()
                    .filter(poi -> poi.getLng() != 0.0 && poi.getLat() != 0.0)
                    .toList();
        }
        return candidates.stream()
                .filter(poi -> poi.getLng() != 0.0 && poi.getLat() != 0.0)
                .filter(poi -> distanceKm(anchor[1], anchor[0], poi.getLat(), poi.getLng()) <= MAX_POI_DISTANCE_KM)
                .sorted(Comparator.comparingDouble(poi -> distanceKm(anchor[1], anchor[0], poi.getLat(), poi.getLng())))
                .toList();
    }

    private int planCount(Map<String, Object> intent) {
        Object value = intent.get("requestedPlanCount");
        if (value instanceof Number number) {
            return clamp(number.intValue(), 1, 5);
        }
        return 3;
    }

    private int stopCount(Map<String, Object> intent) {
        Object requested = intent.get("requestedStopCount");
        if (requested instanceof Number number) {
            return clamp(number.intValue(), 3, 6);
        }
        String preference = String.valueOf(intent.getOrDefault("stopCountPreference", ""));
        if (preference.contains("简洁")) return 3;
        if (preference.contains("丰富")) return 5;
        int duration = durationMinutes(intent);
        if (duration >= 360) return 5;
        if (duration >= 300) return 4;
        return 3;
    }

    private boolean fitsDuration(Map<String, Object> intent, int totalMinutes) {
        int duration = durationMinutes(intent);
        if (duration <= 0) {
            return true;
        }
        int tolerance = Math.max(30, duration / 5);
        return totalMinutes <= duration + tolerance;
    }

    private double durationScore(Map<String, Object> intent, int totalMinutes) {
        int duration = durationMinutes(intent);
        if (duration <= 0) {
            return 10.0;
        }
        return Math.max(0, 100 - Math.abs(duration - totalMinutes)) / 5.0;
    }

    private String durationReason(Map<String, Object> intent) {
        int duration = durationMinutes(intent);
        if (duration <= 0) {
            return "时间安排按已补充信息动态控制";
        }
        return "贴合你填写的约" + Math.round(duration / 60.0 * 10.0) / 10.0 + "小时安排";
    }

    @SuppressWarnings("unchecked")
    private int durationMinutes(Map<String, Object> intent) {
        Object timeWindowValue = intent.get("time_window");
        Map<String, Object> timeWindow = timeWindowValue instanceof Map<?, ?> ? (Map<String, Object>) timeWindowValue : Map.of();
        Object duration = timeWindow.get("durationMinutes");
        if (duration instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private Poi chooseActivity(UUID planId, List<Poi> activities, int offset) {
        Poi activity = activities.get(offset);
        if (!allowMockPoi) {
            return activity;
        }
        if (tools.hasTicket(planId, activity)) {
            return activity;
        }
        Poi replacement = activities.stream().filter(poi -> !poi.isTicketProblem()).findFirst().orElse(activity);
        tools.recovery(planId, "票务不可用", activity.getName(), replacement.getName());
        return replacement;
    }

    private Poi chooseRestaurant(UUID planId, List<Poi> dining, int offset) {
        Poi restaurant = dining.get(offset);
        if (!allowMockPoi) {
            return restaurant;
        }
        if (tools.hasSeat(planId, restaurant)) {
            return restaurant;
        }
        Poi replacement = dining.stream().filter(poi -> !poi.isSeatProblem()).findFirst().orElse(restaurant);
        tools.recovery(planId, "座位不可用", restaurant.getName(), replacement.getName());
        return replacement;
    }

    private List<Poi> buildStops(List<Poi> activities, List<Poi> dining, List<Poi> extras, Poi activity,
                                 Poi restaurant, int stopCount, int offset) {
        List<Poi> stops = new ArrayList<>();
        stops.add(activity);
        for (int i = 3; i < stopCount; i++) {
            Poi candidate = i % 2 == 1 && activities.size() > 1
                    ? activities.get((offset + i) % activities.size())
                    : chooseExtra(extras, activity, offset + i);
            if (stops.stream().noneMatch(poi -> poi.getName().equals(candidate.getName()))
                    && !candidate.getName().equals(restaurant.getName())) {
                stops.add(candidate);
            }
        }
        stops.add(restaurant);
        Poi extra = chooseExtra(extras, activity, offset);
        if (stops.stream().noneMatch(poi -> poi.getName().equals(extra.getName()))) {
            stops.add(extra);
        }
        return stops;
    }

    private Poi chooseExtra(List<Poi> extras, Poi activity, int offset) {
        Poi extra = extras.get(offset % extras.size());
        if (!extra.getName().equals(activity.getName())) {
            return extra;
        }
        return extras.get((offset + 1) % extras.size());
    }

    private Poi extendedPoi(Poi original, int extraMinutes) {
        return poiWithDuration(original, original.getDurationMinutes() + Math.max(0, extraMinutes));
    }

    private List<Poi> fitStopDurations(List<Poi> stops, int durationMinutes) {
        if (durationMinutes <= 0 || stops.isEmpty()) {
            return stops;
        }
        int targetStopMinutes = Math.max(120, durationMinutes - 35);
        int currentStopMinutes = stops.stream().mapToInt(Poi::getDurationMinutes).sum();
        if (currentStopMinutes <= targetStopMinutes) {
            return stops;
        }
        double ratio = Math.max(0.55, targetStopMinutes / (double) currentStopMinutes);
        return stops.stream()
                .map(poi -> {
                    int minimum = poi.getType() == PoiType.DINING ? 55 : poi.getType() == PoiType.EXTRA ? 25 : 45;
                    int adjusted = Math.max(minimum, (int) Math.round(poi.getDurationMinutes() * ratio));
                    return poiWithDuration(poi, adjusted);
                })
                .toList();
    }

    private Poi poiWithDuration(Poi original, int durationMinutes) {
        return new Poi(
                original.getName(),
                original.getType(),
                original.getSubtype(),
                original.getAddress(),
                original.getLng(),
                original.getLat(),
                durationMinutes,
                original.getAvgPrice(),
                original.getRating(),
                original.isKidFriendly(),
                original.isLowCalorie(),
                original.isIndoor(),
                original.isSocial(),
                original.isTicketProblem(),
                original.isSeatProblem()
        );
    }

    private Map<String, Object> option(int rank, List<Poi> stops, Map<String, Object> route, int totalMinutes,
                                       Map<String, Object> intent, Map<String, Object> weather) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Poi poi = stops.get(i);
            Map<String, Object> timelineItem = new LinkedHashMap<>();
            timelineItem.put("time", startTime(intent, i));
            timelineItem.put("type", poi.getType() == PoiType.DINING ? "餐饮" : i == 2 ? "补充" : "活动");
            timelineItem.put("name", poi.getName());
            timelineItem.put("subtype", poi.getSubtype());
            timelineItem.put("address", poi.getAddress());
            timelineItem.put("durationMinutes", poi.getDurationMinutes());
            timelineItem.put("avgPrice", poi.getAvgPrice());
            timelineItem.put("rating", poi.getRating());
            timelineItem.put("kidFriendly", poi.isKidFriendly());
            timelineItem.put("lowCalorie", poi.isLowCalorie());
            timelineItem.put("lng", poi.getLng());
            timelineItem.put("lat", poi.getLat());
            timeline.add(timelineItem);
        }
        int groupTotal = groupTotal(intent);
        int budget = stops.stream().mapToInt(Poi::getAvgPrice).sum() * groupTotal;
        double score = stops.stream().mapToDouble(Poi::getRating).average().orElse(4.0) * 20
                + durationScore(intent, totalMinutes)
                + Math.max(0, 12.0 - ((Number) route.getOrDefault("distanceKm", 0)).doubleValue());

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("rank", rank);
        option.put("score", Math.round(score * 10.0) / 10.0);
        option.put("name", rank == 1 ? "稳妥轻松方案" : rank == 2 ? "体验丰富方案" : "备用省心方案");
        option.put("tagline", rank == 1 ? "距离更近，节奏更稳" : rank == 2 ? "内容更丰富，适合慢慢玩" : "临时调整也容易执行");
        option.put("timeline", timeline);
        option.put("totalMinutes", totalMinutes);
        option.put("budgetEstimate", budget);
        option.put("route", route);
        option.put("fitReasons", List.of("覆盖活动、餐饮和补充行程", durationReason(intent), "地点均来自高德真实结果"));
        option.put("riskNotes", List.of("高峰期建议提前15分钟出发", weatherSuggestion(weather), "出发前建议再次确认营业状态"));
        option.put("executionList", List.of("活动购票", "餐厅订座或排队", "配送安排", "生成分享消息"));
        option.put("firstStop", stops.get(0).getName());
        option.put("diningName", stops.stream().filter(poi -> poi.getType() == PoiType.DINING).findFirst().orElse(stops.get(1)).getName());
        option.put("lastStop", stops.get(stops.size() - 1).getName());
        return option;
    }

    private int groupTotal(Map<String, Object> intent) {
        Object groupValue = intent.get("group");
        if (groupValue instanceof Map<?, ?> group && group.get("total") instanceof Number total) {
            return Math.max(1, total.intValue());
        }
        return 2;
    }

    private List<Map<String, Object>> weatherWarnings(Map<String, Object> weather) {
        if (weather == null || weather.isEmpty()) {
            return List.of(warning("天气暂不可用", "建议出发前自行确认天气变化。"));
        }
        return List.of(warning("天气建议", weatherSuggestion(weather)));
    }

    private Map<String, Object> warning(String title, String message) {
        return Map.of("title", title, "message", message);
    }

    private String weatherSuggestion(Map<String, Object> weather) {
        if (weather == null || weather.isEmpty()) {
            return "天气暂不可用，建议出发前自行确认天气变化。";
        }
        Object suggestion = weather.get("suggestion");
        if (suggestion != null && !String.valueOf(suggestion).isBlank()) {
            return String.valueOf(suggestion);
        }
        return "出发前建议再次确认天气变化。";
    }

    @SuppressWarnings("unchecked")
    private String startTime(Map<String, Object> intent, int index) {
        Object timeWindowValue = intent.get("time_window");
        Map<String, Object> timeWindow = timeWindowValue instanceof Map<?, ?> ? (Map<String, Object>) timeWindowValue : Map.of();
        String start = String.valueOf(timeWindow.get("start"));
        try {
            return LocalTime.parse(start).plusMinutes(index * 95L).toString();
        } catch (DateTimeParseException e) {
            return "--:--";
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private List<String> castStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private Integer intValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer extractNumber(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,5})").matcher(text == null ? "" : text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private Integer extractPlanCount(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d)\\s*(套|个)?\\s*方案").matcher(text == null ? "" : text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private String extractExcludedPoi(String message, Map<String, Object> previousOption) {
        String text = message == null ? "" : message;
        if (!(text.contains("不喜欢") || text.contains("关门") || text.contains("换掉") || text.contains("不要")
                || text.contains("换餐厅") || text.contains("换饭店"))) {
            return null;
        }
        List<Map<String, Object>> timeline = castList(previousOption.get("timeline"));
        if (timeline.isEmpty()) {
            return null;
        }
        for (Map<String, Object> item : timeline) {
            String name = String.valueOf(item.getOrDefault("name", ""));
            if (!name.isBlank() && text.contains(name)) {
                return name;
            }
        }
        if (text.contains("换餐厅") || text.contains("换饭店")) {
            return timeline.stream()
                    .filter(item -> "餐饮".equals(String.valueOf(item.get("type"))))
                    .map(item -> String.valueOf(item.getOrDefault("name", "")))
                    .filter(name -> !name.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        if (text.contains("当前店") || text.contains("这家") || text.contains("关门")) {
            return timeline.get(timeline.size() - 1).get("name") == null
                    ? null
                    : String.valueOf(timeline.get(timeline.size() - 1).get("name"));
        }
        return null;
    }

    private String signature(List<Poi> stops) {
        return String.join("|", stops.stream().map(Poi::getName).toList());
    }

    private double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double radius = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @SuppressWarnings("unchecked")
    private double[] anchor(Map<String, Object> intent) {
        if (intent != null) {
            Object value = intent.get("location");
            Map<String, Object> location = value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
            Object lng = location.get("lng");
            Object lat = location.get("lat");
            if (lng instanceof Number lngNumber && lat instanceof Number latNumber) {
                return new double[] {lngNumber.doubleValue(), latNumber.doubleValue()};
            }
        }
        return null;
    }

    private PlanResponse toResponse(UUID id) {
        PlanSession session = planSessionRepository.findById(id).orElseThrow();
        Map<String, Object> intent = fromJson(session.getIntentJson());
        Map<String, Object> result = fromJson(session.getResultJson());
        List<Map<String, Object>> options = castList(result.getOrDefault("options", List.of()));
        Map<String, Object> clarification = fromAny(result.get("clarification"));
        Map<String, Object> weather = fromAny(result.get("weather"));
        List<Map<String, Object>> warnings = castWarningList(result.getOrDefault("warnings", List.of()));
        List<Map<String, Object>> trace = toolCallLogRepository.findByPlanSessionIdOrderByCreatedAt(id).stream()
                .map(this::tracePayload)
                .toList();
        return new PlanResponse(id, session.getStatus().name(), intent, options, trace, fromJson(session.getExecutionJson()),
                clarification, weather, warnings.stream().map(this::warningText).toList());
    }

    private String warningText(Map<String, Object> warning) {
        String title = String.valueOf(warning.getOrDefault("title", ""));
        String message = String.valueOf(warning.getOrDefault("message", ""));
        if (title.isBlank()) {
            return message;
        }
        if (message.isBlank()) {
            return title;
        }
        return title + "：" + message;
    }

    private Map<String, Object> findOption(UUID id, int rank) {
        return planOptionRepository.findByPlanSessionIdOrderByRankNo(id).stream()
                .filter(option -> option.getRankNo() == rank)
                .findFirst()
                .map(option -> fromJson(option.getOptionJson()))
                .orElseThrow(() -> new IllegalArgumentException("没有找到这个方案，请选择已生成的方案编号。"));
    }

    private Map<String, Object> findOptionOrEmpty(UUID id, int rank) {
        return planOptionRepository.findByPlanSessionIdOrderByRankNo(id).stream()
                .filter(option -> option.getRankNo() == rank)
                .findFirst()
                .map(option -> fromJson(option.getOptionJson()))
                .orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> tracePayload(ToolCallLog log) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", log.getToolName());
        item.put("status", log.getStatus());
        item.put("durationMs", log.getDurationMs());
        item.put("input", fromJson(log.getInputJson()));
        Map<String, Object> output = fromJson(log.getOutputJson());
        item.put("output", output);
        item.put("provider", output.getOrDefault("provider", "local"));
        item.put("mode", output.getOrDefault("mode", "mock"));
        item.put("sourceUrl", output.getOrDefault("sourceUrl", ""));
        item.put("externalStatus", output.getOrDefault("externalStatus", ""));
        return item;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?>) {
            return (List<Map<String, Object>>) value;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castWarningList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : Map.<String, Object>of("message", String.valueOf(item)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromAny(Object value) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }
}
