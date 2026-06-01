package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.dto.ApiDtos.PlanRequest;
import com.localagent.dto.ApiDtos.PlanResponse;
import com.localagent.model.ChatMessage;
import com.localagent.model.ChatMessageKind;
import com.localagent.model.FeedbackEvent;
import com.localagent.model.PlanOption;
import com.localagent.model.PlanSession;
import com.localagent.model.PlanStatus;
import com.localagent.model.PlanThread;
import com.localagent.model.PlanTurnType;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningService {
    private static final List<Double> POI_RADIUS_STEPS_KM = List.of(12.0, 18.0, 25.0);
    private static final double WALKING_ROUTE_LIMIT_KM = 3.0;
    private static final double MIXED_ROUTE_LIMIT_KM = 10.0;
    private static final double DRIVING_ROUTE_LIMIT_KM = 25.0;
    private static final double WALKING_DIRECT_PREFILTER_KM = 4.0;
    private static final double MIXED_DIRECT_PREFILTER_KM = 12.5;
    private static final double DRIVING_DIRECT_PREFILTER_KM = 28.0;
    private static final int MAX_ROUTE_ATTEMPTS = 4;

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
    private final HistoryService historyService;
    private final ObjectMapper objectMapper;
    private final boolean allowMockPoi;
    private final ExecutorService optionalToolExecutor = Executors.newFixedThreadPool(4);

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
                           HistoryService historyService,
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
        this.historyService = historyService;
        this.objectMapper = objectMapper;
        this.allowMockPoi = allowMockPoi;
    }

    public PlanResponse createPlan(String token, String clientId, PlanRequest request) {
        return createPlanInternal(token, clientId, request, turnTypeFor(request));
    }

    public PlanResponse createPlan(String token, PlanRequest request) {
        return createPlan(token, "test-client", request);
    }

    private PlanResponse createPlanInternal(String token, String clientId, PlanRequest request, PlanTurnType turnType) {
        String message = request == null || request.message() == null ? "" : request.message().trim();
        PlanThread thread = request != null && request.threadId() != null
                ? historyService.requireThread(request.threadId(), clientId)
                : historyService.createThread(clientId, message);
        UUID parentPlanSessionId = request == null ? null : request.previousPlanId();
        if (!message.isBlank()) {
            historyService.appendUserText(thread.getId(), parentPlanSessionId, message);
        }
        PlanSession session = planSessionRepository.save(
                PlanSession.create(thread.getId(), parentPlanSessionId, turnType, token, message)
        );
        historyService.markLatestPlanSession(thread, session.getId());
        try {
            Map<String, Object> intent = buildIntent(session.getId(), request, message);
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
                ChatMessage assistant = historyService.appendAssistant(thread.getId(), session.getId(), parentPlanSessionId,
                        ChatMessageKind.ASSISTANT_CLARIFICATION,
                        "还需要补齐几个关键信息，补齐后我再查询真实地点并生成方案。",
                        clarificationPayload(session.getId(), intent, clarification, result));
                return toResponse(session.getId(), assistant.getId());
            }

            Map<String, Object> planningIntent = intent;
            String planningMessage = message;
            CompletableFuture<Map<String, Object>> weatherFuture = CompletableFuture.supplyAsync(
                    () -> weatherTool.weather(session.getId(), planningIntent), optionalToolExecutor);
            CompletableFuture<List<Map<String, Object>>> evidenceFuture = CompletableFuture.supplyAsync(
                    () -> searchVerifierAgent.verify(session.getId(), planningIntent, planningMessage), optionalToolExecutor);
            List<Poi> candidates = poiSearchTool.searchPois(session.getId(), intent);
            Map<String, Object> weather = optionalWeather(session.getId(), weatherFuture);
            List<Map<String, Object>> webEvidence = optionalEvidence(session.getId(), evidenceFuture);
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
            ChatMessage assistant = historyService.appendAssistant(thread.getId(), session.getId(), parentPlanSessionId,
                    ChatMessageKind.ASSISTANT_PLAN_RESULT,
                    turnType == PlanTurnType.CLARIFICATION
                            ? "信息补齐了，已生成 3 套方案，下面展开查看地图和路线。"
                            : "方案已生成，下面展开查看地图和路线。",
                    planResultPayload(session.getId(), intent, result, Map.of(), "chat", "plans"));
            return toResponse(session.getId(), assistant.getId());
        } catch (RuntimeException ex) {
            historyService.appendAssistant(thread.getId(), session.getId(), parentPlanSessionId,
                    ChatMessageKind.ASSISTANT_ERROR,
                    userFacingError(ex),
                    Map.of(
                            "status", statusOf(ex),
                            "provider", providerOf(ex),
                            "rawError", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                    ));
            throw ex;
        } finally {
            routeEstimateTool.clearCache(session.getId());
        }
    }

    private PlanTurnType turnTypeFor(PlanRequest request) {
        if (request == null || request.previousPlanId() == null) {
            return PlanTurnType.INITIAL;
        }
        return PlanTurnType.CLARIFICATION;
    }

    private Map<String, Object> buildIntent(UUID planId, PlanRequest request, String message) {
        Map<String, Object> parsed = shouldSkipIntentParse(request)
                ? new LinkedHashMap<>()
                : intentParserAgent.parse(planId, message);
        Map<String, Object> previous = previousIntent(request);
        Map<String, Object> intent = mergeIntent(previous, parsed);
        String rawMessage = rawMessage(previous, message);
        intent.put("rawMessage", rawMessage);
        clarificationService.ensureUserFacts(intent, rawMessage);
        return intent;
    }

    private boolean shouldSkipIntentParse(PlanRequest request) {
        return request != null
                && request.previousPlanId() != null
                && request.clarificationAnswers() != null
                && !request.clarificationAnswers().isEmpty();
    }

    private Map<String, Object> previousIntent(PlanRequest request) {
        if (request == null || request.previousPlanId() == null) {
            return new LinkedHashMap<>();
        }
        return planSessionRepository.findById(request.previousPlanId())
                .map(previous -> fromJson(previous.getIntentJson()))
                .orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> mergeIntent(Map<String, Object> previous, Map<String, Object> parsed) {
        Map<String, Object> merged = new LinkedHashMap<>(previous == null ? Map.of() : previous);
        if (parsed == null || parsed.isEmpty()) {
            return merged;
        }
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (hasUsefulValue(value)) {
                Object existing = merged.get(key);
                if (existing instanceof Map<?, ?> && value instanceof Map<?, ?>) {
                    merged.put(key, mergeNested(castMap(existing), castMap(value)));
                } else {
                    merged.put(key, value);
                }
            }
        }
        return merged;
    }

    private Map<String, Object> mergeNested(Map<String, Object> previous, Map<String, Object> parsed) {
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        parsed.forEach((key, value) -> {
            if (hasUsefulValue(value)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private boolean hasUsefulValue(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank() && !"null".equals(text);
        if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(this::hasUsefulValue);
        if (value instanceof List<?> list) return !list.isEmpty();
        return true;
    }

    private String rawMessage(Map<String, Object> previous, String message) {
        String previousRaw = String.valueOf(castMap(previous.get("userFacts")).getOrDefault("rawMessage",
                previous.getOrDefault("rawMessage", ""))).trim();
        if (previousRaw.isBlank() || message == null || message.isBlank() || previousRaw.contains(message)) {
            return previousRaw.isBlank() ? (message == null ? "" : message) : previousRaw;
        }
        return previousRaw + "。用户补充：" + message;
    }

    private Map<String, Object> optionalWeather(UUID planId, CompletableFuture<Map<String, Object>> future) {
        try {
            return future.get(3200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            toolTraceService.trace(planId, "AmapWeatherTool", "fallback", System.currentTimeMillis(),
                    Map.of("mode", "optional-timeout"),
                    Map.of("provider", "amap", "mode", "fallback", "reason", safeReason(e),
                            "suggestion", "天气暂不可用，建议出发前自行确认天气变化。"));
            return Map.of("available", false, "suggestion", "天气暂不可用，建议出发前自行确认天气变化。");
        }
    }

    private List<Map<String, Object>> optionalEvidence(UUID planId, CompletableFuture<List<Map<String, Object>>> future) {
        try {
            return future.get(3200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            toolTraceService.trace(planId, "WebSearchTool", "fallback", System.currentTimeMillis(),
                    Map.of("mode", "optional-timeout"),
                    Map.of("provider", "search", "mode", "fallback", "reason", safeReason(e)));
            return List.of();
        }
    }

    public PlanResponse createPlan(String token, String message) {
        return createPlan(token, "test-client", new PlanRequest(message, null, null, null, null, null));
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
        return confirm(id, "test-client", rank);
    }

    @Transactional
    public PlanResponse confirm(UUID id, String clientId, int rank) {
        PlanSession session = planSessionRepository.findById(id).orElseThrow();
        PlanThread thread = historyService.requireThread(session.getThreadId(), clientId);
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
        ChatMessage assistant = historyService.appendAssistant(thread.getId(), session.getId(), session.getParentPlanSessionId(),
                ChatMessageKind.ASSISTANT_PLAN_RESULT,
                "已确认执行，门票、订座和分享消息都已安排。",
                confirmPayload(session, execution));
        return toResponse(id, assistant.getId());
    }

    public PlanResponse feedback(UUID id, String message) {
        return feedback(id, "test-client", message);
    }

    public PlanResponse feedback(UUID id, String clientId, String message) {
        PlanSession previous = planSessionRepository.findById(id).orElseThrow();
        historyService.requireThread(previous.getThreadId(), clientId);
        feedbackEventRepository.save(new FeedbackEvent(id, message));
        Map<String, Object> previousIntent = fromJson(previous.getIntentJson());
        Map<String, Object> previousOption = findOptionOrEmpty(id, 1);
        Map<String, Object> patch = feedbackPatch(id, message, previousIntent, previousOption);
        return createPlanInternal(previous.getSessionToken(), clientId, new PlanRequest(
                stringValue(message),
                intValue(patch.get("requestedPlanCount")),
                stringValue(patch.get("stopCountPreference")),
                patch,
                previous.getId(),
                previous.getThreadId()
        ), PlanTurnType.FEEDBACK);
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
        CandidatePool candidatePool = nearbyCandidates(candidates, intent);
        List<Poi> nearby = candidatePool.pois();
        if (candidatePool.expanded()) {
            warnings.add(warning("已扩大搜索范围",
                    "附近真实地点不足，已把候选半径放宽到约 " + Math.round(candidatePool.radiusKm()) + " 公里；方案会优先控制路线成本。"));
        }
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
        int maxAttempts = Math.min(MAX_ROUTE_ATTEMPTS,
                Math.max(minimumPlanCount, activities.size() * dining.size() * Math.max(1, extras.size())));
        for (int attempt = 0; attempt < maxAttempts && options.size() < planCount; attempt++) {
            Poi activity = chooseActivity(planId, activities, attempt % activities.size());
            Poi restaurant = chooseRestaurant(planId, dining, (attempt / Math.max(1, activities.size())) % dining.size());
            Poi extra = extras.get((attempt / Math.max(1, activities.size() * dining.size())) % extras.size());
            List<Poi> stops = fitStopDurations(buildStops(activities, dining, extras, activity, restaurant, stopCount, attempt),
                    durationMinutes(intent));
            if (stops.stream().noneMatch(poi -> poi.getName().equals(extra.getName()))
                    && !extra.getName().equals(activity.getName())
                    && !extra.getName().equals(restaurant.getName())) {
                stops = replaceLastNonDiningStop(stops, extra);
            }
            if (!hasRequiredPoiCoverage(stops)) {
                tools.recovery(planId, "POI类型不完整", signature(stops), "跳过该候选");
                continue;
            }
            if (directRouteDistanceKm(stops) > directRoutePrefilterKm(intent)) {
                tools.recovery(planId, "路线直线距离预筛过远", signature(stops), "跳过该候选");
                continue;
            }
            String signature = signature(stops);
            if (!signatures.add(signature)) {
                continue;
            }

            Map<String, Object> route;
            try {
                route = routeEstimateTool.route(planId, stops);
            } catch (PlanBlockedException e) {
                tools.recovery(planId, "路线生成失败", signature(stops), "跳过该候选");
                continue;
            }
            double distanceKm = ((Number) route.getOrDefault("distanceKm", 0)).doubleValue();
            if (distanceKm > maxRouteDistanceKm(intent)) {
                tools.recovery(planId, "路线距离过远", signature, "跳过该候选");
                continue;
            }
            int totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
            if (isTooShort(intent, totalMinutes)) {
                stops = extendFlexibleStop(stops, durationMinutes(intent) - totalMinutes);
                totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
            }
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
            int newRank = index + 1;
            sorted.get(index).put("rank", newRank);
            sorted.get(index).put("name", newRank == 1 ? "稳妥轻松方案" : newRank == 2 ? "体验丰富方案" : "备用省心方案");
            sorted.get(index).put("tagline", newRank == 1 ? "距离更近，节奏更稳" : newRank == 2 ? "内容更丰富，适合慢慢玩" : "临时调整也容易执行");
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

    private CandidatePool nearbyCandidates(List<Poi> candidates, Map<String, Object> intent) {
        double[] anchor = anchor(intent);
        if (anchor == null) {
            return new CandidatePool(candidates.stream()
                    .filter(poi -> poi.getLng() != 0.0 && poi.getLat() != 0.0)
                    .toList(), 0.0, false);
        }
        for (double radiusKm : POI_RADIUS_STEPS_KM) {
            List<Poi> scoped = candidatesWithinRadius(candidates, anchor, radiusKm);
            if (hasCandidateCoverage(scoped)) {
                return new CandidatePool(scoped, radiusKm, radiusKm > POI_RADIUS_STEPS_KM.get(0));
            }
        }
        double radiusKm = POI_RADIUS_STEPS_KM.get(POI_RADIUS_STEPS_KM.size() - 1);
        return new CandidatePool(candidatesWithinRadius(candidates, anchor, radiusKm), radiusKm, true);
    }

    private List<Poi> candidatesWithinRadius(List<Poi> candidates, double[] anchor, double radiusKm) {
        return candidates.stream()
                .filter(poi -> poi.getLng() != 0.0 && poi.getLat() != 0.0)
                .filter(poi -> distanceKm(anchor[1], anchor[0], poi.getLat(), poi.getLng()) <= radiusKm)
                .sorted(Comparator.comparingDouble(poi -> distanceKm(anchor[1], anchor[0], poi.getLat(), poi.getLng())))
                .toList();
    }

    private boolean hasCandidateCoverage(List<Poi> candidates) {
        long activityCount = candidates.stream()
                .filter(poi -> poi.getType() == PoiType.ENTERTAINMENT || poi.getType() == PoiType.CULTURE)
                .count();
        long diningCount = candidates.stream().filter(poi -> poi.getType() == PoiType.DINING).count();
        return activityCount >= 2 && diningCount >= 1 && candidates.size() >= 4;
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
        return totalMinutes >= duration - tolerance && totalMinutes <= duration + tolerance;
    }

    private boolean isTooShort(Map<String, Object> intent, int totalMinutes) {
        int duration = durationMinutes(intent);
        if (duration <= 0) {
            return false;
        }
        int tolerance = Math.max(30, duration / 5);
        return totalMinutes < duration - tolerance;
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
        tools.recovery(planId, "\u7968\u52a1\u4e0d\u53ef\u7528", activity.getName(), "\u4fdd\u7559\u5019\u9009\u5e76\u63d0\u793a\u98ce\u9669");
        return activity;
    }

    private Poi chooseRestaurant(UUID planId, List<Poi> dining, int offset) {
        Poi restaurant = dining.get(offset);
        if (!allowMockPoi) {
            return restaurant;
        }
        if (tools.hasSeat(planId, restaurant)) {
            return restaurant;
        }
        tools.recovery(planId, "\u5ea7\u4f4d\u4e0d\u53ef\u7528", restaurant.getName(), "\u4fdd\u7559\u5019\u9009\u5e76\u63d0\u793a\u98ce\u9669");
        return restaurant;
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

    private List<Poi> replaceLastNonDiningStop(List<Poi> stops, Poi replacement) {
        if (stops.isEmpty()) {
            return stops;
        }
        List<Poi> adjusted = new ArrayList<>(stops);
        for (int i = adjusted.size() - 1; i >= 0; i--) {
            if (adjusted.get(i).getType() != PoiType.DINING) {
                adjusted.set(i, replacement);
                return adjusted;
            }
        }
        return adjusted;
    }

    private Poi extendedPoi(Poi original, int extraMinutes) {
        return poiWithDuration(original, original.getDurationMinutes() + Math.max(0, extraMinutes));
    }

    private List<Poi> extendFlexibleStop(List<Poi> stops, int extraMinutes) {
        if (extraMinutes <= 0 || stops.isEmpty()) {
            return stops;
        }
        int index = 0;
        for (int i = stops.size() - 1; i >= 0; i--) {
            if (stops.get(i).getType() != PoiType.DINING) {
                index = i;
                break;
            }
        }
        List<Poi> adjusted = new ArrayList<>(stops);
        adjusted.set(index, extendedPoi(adjusted.get(index), extraMinutes));
        return adjusted;
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
        // 从 route 中读取分段时间，用于准确计算每站开始时间
        List<Integer> segmentMinutes = castSegmentMinutes(route.get("segmentMinutes"));
        LocalTime currentTime = parseStartTime(intent);

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Poi poi = stops.get(i);
            Map<String, Object> timelineItem = new LinkedHashMap<>();
            timelineItem.put("time", currentTime.toString());
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
            // 累加当前站点停留时间 + 到下一站的交通时间
            currentTime = currentTime.plusMinutes(poi.getDurationMinutes());
            if (i < segmentMinutes.size()) {
                currentTime = currentTime.plusMinutes(segmentMinutes.get(i));
            }
        }
        int groupTotal = groupTotal(intent);
        int budget = stops.stream().mapToInt(Poi::getAvgPrice).sum() * groupTotal;
        double distanceKm = ((Number) route.getOrDefault("distanceKm", 0)).doubleValue();

        // 归一化评分：三项各占 0-40/0-20/0-40，总分 0-100
        double ratingScore = stops.stream().mapToDouble(Poi::getRating).average().orElse(4.0) / 5.0 * 40.0;
        double durScore = durationScore(intent, totalMinutes);
        double distScore = Math.max(0, 1.0 - distanceKm / maxRouteDistanceKm(intent)) * 40.0;
        double score = ratingScore + durScore + distScore;

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("rank", rank);
        option.put("score", Math.round(score * 10.0) / 10.0);
        // name/tagline 暂时用 rank 占位，排序后会被覆盖
        option.put("name", "方案 " + rank);
        option.put("tagline", "基于真实周边地点生成");
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
    private LocalTime parseStartTime(Map<String, Object> intent) {
        Object timeWindowValue = intent.get("time_window");
        Map<String, Object> timeWindow = timeWindowValue instanceof Map<?, ?> ? (Map<String, Object>) timeWindowValue : Map.of();
        String start = String.valueOf(timeWindow.get("start"));
        try {
            return LocalTime.parse(start);
        } catch (DateTimeParseException e) {
            return LocalTime.of(14, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Integer> castSegmentMinutes(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .map(item -> item instanceof Number n ? n.intValue() : 0)
                .toList();
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

    private String safeReason(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
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

    private double maxRouteDistanceKm(Map<String, Object> intent) {
        return switch (transportProfile(intent)) {
            case "walking" -> WALKING_ROUTE_LIMIT_KM;
            case "driving" -> DRIVING_ROUTE_LIMIT_KM;
            default -> MIXED_ROUTE_LIMIT_KM;
        };
    }

    private double directRoutePrefilterKm(Map<String, Object> intent) {
        return switch (transportProfile(intent)) {
            case "walking" -> WALKING_DIRECT_PREFILTER_KM;
            case "driving" -> DRIVING_DIRECT_PREFILTER_KM;
            default -> MIXED_DIRECT_PREFILTER_KM;
        };
    }

    private String transportProfile(Map<String, Object> intent) {
        String text = String.join(" ",
                String.valueOf(intent.getOrDefault("rawMessage", "")),
                String.valueOf(intent.getOrDefault("stopCountPreference", "")),
                String.valueOf(intent.getOrDefault("soft_preferences", "")),
                String.valueOf(intent.getOrDefault("hard_constraints", "")),
                String.valueOf(castMap(intent.get("userFacts")).getOrDefault("answers", "")));
        if (text.contains("\u5168\u7a0b\u6b65\u884c") || text.contains("\u53ea\u6b65\u884c")
                || text.contains("\u7eaf\u6b65\u884c") || text.contains("\u4e0d\u8981\u6253\u8f66")) {
            return "walking";
        }
        if (text.contains("\u5f00\u8f66") || text.contains("\u81ea\u9a7e") || text.contains("\u6253\u8f66")
                || text.contains("\u7f51\u7ea6\u8f66") || text.contains("\u8de8\u533a") || text.contains("\u8fdc\u4e00\u70b9")) {
            return "driving";
        }
        return "mixed";
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

    private record CandidatePool(List<Poi> pois, double radiusKm, boolean expanded) {}

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

    private String userFacingError(RuntimeException ex) {
        if (ex instanceof PlanBlockedException blocked && blocked.getMessage() != null && !blocked.getMessage().isBlank()) {
            return blocked.getMessage();
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return ex.getMessage();
        }
        return "抱歉，刚刚网络有点小问题，要不要再试一次？";
    }

    private String statusOf(RuntimeException ex) {
        if (ex instanceof PlanBlockedException) {
            return "BLOCKED";
        }
        if (ex instanceof IllegalArgumentException) {
            return "INVALID_REQUEST";
        }
        if (ex instanceof IllegalStateException) {
            return "NOT_READY";
        }
        return "ERROR";
    }

    private String providerOf(RuntimeException ex) {
        if (ex instanceof PlanBlockedException blocked && blocked.getProvider() != null) {
            return blocked.getProvider();
        }
        return "local";
    }

    private Map<String, Object> clarificationPayload(UUID planId, Map<String, Object> intent,
                                                     Map<String, Object> clarification, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", planId);
        payload.put("intent", intent);
        payload.put("clarification", clarification);
        payload.put("warnings", result.getOrDefault("warnings", List.of()));
        payload.put("currentView", "chat");
        payload.put("currentStep", "clarify");
        payload.put("mapOrigin", fromAny(intent.get("location")));
        return payload;
    }

    private Map<String, Object> planResultPayload(UUID planId, Map<String, Object> intent, Map<String, Object> result,
                                                  Map<String, Object> execution, String currentView, String currentStep) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", planId);
        payload.put("intent", intent);
        payload.put("options", result.getOrDefault("options", List.of()));
        payload.put("clarification", result.getOrDefault("clarification", Map.of()));
        payload.put("weather", result.getOrDefault("weather", Map.of()));
        payload.put("warnings", result.getOrDefault("warnings", List.of()));
        payload.put("execution", execution);
        payload.put("currentView", currentView);
        payload.put("currentStep", currentStep);
        payload.put("mapOrigin", fromAny(intent.get("location")));
        if (!execution.isEmpty()) {
            payload.put("executionSteps", executionSteps(execution));
        }
        return payload;
    }

    private Map<String, Object> confirmPayload(PlanSession session, Map<String, Object> execution) {
        Map<String, Object> intent = fromJson(session.getIntentJson());
        Map<String, Object> result = fromJson(session.getResultJson());
        return planResultPayload(session.getId(), intent, result, execution, "execute", "plans");
    }

    private List<Map<String, Object>> executionSteps(Map<String, Object> execution) {
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> orders = castList(execution.get("orders"));
        for (Map<String, Object> order : orders) {
            steps.add(Map.of(
                    "name", String.valueOf(order.getOrDefault("targetName", "执行事项")),
                    "status", "done"
            ));
        }
        Map<String, Object> gift = fromAny(execution.get("gift"));
        if (!gift.isEmpty()) {
            steps.add(Map.of("name", String.valueOf(gift.getOrDefault("targetName", "礼物配送已安排")), "status", "done"));
        }
        if (execution.get("shareMessage") != null) {
            steps.add(Map.of("name", "分享消息已生成", "status", "done"));
        }
        return steps;
    }

    private PlanResponse toResponse(UUID id) {
        return toResponse(id, null);
    }

    private PlanResponse toResponse(UUID id, UUID assistantMessageId) {
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
        return new PlanResponse(id, session.getThreadId(), session.getStatus().name(), intent, options, trace,
                fromJson(session.getExecutionJson()), clarification, weather,
                warnings.stream().map(this::warningText).toList(), assistantMessageId);
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
