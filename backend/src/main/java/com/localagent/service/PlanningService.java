package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningService {
    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
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
    private final AmapPoiDetailTool poiDetailTool;
    private final PlanValidationService planValidationService;
    private final PromptCatalog promptCatalog;
    private final ToolTraceService toolTraceService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper;
    private final ExternalClientProperties properties;
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
                           AmapPoiDetailTool poiDetailTool,
                           PlanValidationService planValidationService,
                           PromptCatalog promptCatalog,
                           ToolTraceService toolTraceService,
                           HistoryService historyService,
                           ObjectMapper objectMapper,
                           ExternalClientProperties properties,
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
        this.poiDetailTool = poiDetailTool;
        this.planValidationService = planValidationService;
        this.promptCatalog = promptCatalog;
        this.toolTraceService = toolTraceService;
        this.historyService = historyService;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
        log.info("planning.start threadId={} planId={} turnType={} previousPlanId={} messageLength={}",
                thread.getId(), session.getId(), turnType, parentPlanSessionId, message.length());
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
                log.info("planning.clarification threadId={} planId={} fields={}",
                        thread.getId(), session.getId(), clarification.keySet());
                return toResponse(session.getId(), assistant.getId());
            }

            Map<String, Object> planningIntent = intent;
            String planningMessage = message;
            CompletableFuture<Map<String, Object>> weatherFuture = CompletableFuture.supplyAsync(
                    () -> weatherTool.weather(session.getId(), planningIntent), optionalToolExecutor);
            CompletableFuture<List<Map<String, Object>>> evidenceFuture = CompletableFuture.supplyAsync(
                    () -> searchVerifierAgent.verify(session.getId(), planningIntent, planningMessage), optionalToolExecutor);
            List<Poi> candidates = mergeExplicitPoiCandidates(intent, poiSearchTool.searchPois(session.getId(), intent));
            Map<String, Object> weather = optionalWeather(session.getId(), weatherFuture);
            List<Map<String, Object>> webEvidence = optionalEvidence(session.getId(), evidenceFuture);
            List<Map<String, Object>> warnings = new ArrayList<>(weatherWarnings(weather));
            List<Map<String, Object>> options = buildOptions(session.getId(), intent, candidates, weather, warnings);
            addPoiDetailWarnings(session.getId(), options, warnings);
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
                    planResultPayload(session.getId(), intent, result, Map.of(), "chat", "plans", null));
            log.info("planning.success threadId={} planId={} optionCount={} warnings={}",
                    thread.getId(), session.getId(), options.size(), warnings.size());
            return toResponse(session.getId(), assistant.getId());
        } catch (RuntimeException ex) {
            log.warn("planning.failed threadId={} planId={} status={} provider={} error={}",
                    thread.getId(), session.getId(), statusOf(ex), providerOf(ex), ex.getMessage(), ex);
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
                : parseIntent(planId, message);
        Map<String, Object> previous = previousIntent(request);
        Map<String, Object> intent = mergeIntent(previous, parsed);
        String rawMessage = rawMessage(previous, message);
        intent.put("rawMessage", rawMessage);
        clarificationService.ensureUserFacts(intent, rawMessage);
        return intent;
    }

    private Map<String, Object> parseIntent(UUID planId, String message) {
        String mode = properties.getLlm().getIntentParserMode();
        if ("rule-only".equalsIgnoreCase(mode)) {
            return intentParserAgent.fastParse(planId, message);
        }
        return intentParserAgent.parse(planId, message);
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
        Map<String, Object> execution = tools.executePlan(id, option);
        execution.put("selectedRank", rank);
        session.markCompleted(toJson(execution));
        planSessionRepository.save(session);
        ChatMessage assistant = historyService.appendAssistant(thread.getId(), session.getId(), session.getParentPlanSessionId(),
                ChatMessageKind.ASSISTANT_PLAN_RESULT,
                "已确认执行，门票、订座和分享消息都已安排。",
                confirmPayload(session, execution, rank));
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
        if (hasCompleteExplicitPoiRoute(intent)) {
            return buildExplicitPoiOptions(planId, intent, candidates, weather);
        }
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
            Poi origin = originPoi(intent);
            stops = optimizeStopOrder(origin, stops);
            if (!hasRequiredPoiCoverage(stops)) {
                tools.recovery(planId, "POI类型不完整", signature(stops), "跳过该候选");
                continue;
            }
            List<Poi> routeStops = routeStops(origin, stops);
            if (directRouteDistanceKm(routeStops) > directRoutePrefilterKm(intent)) {
                tools.recovery(planId, "路线直线距离预筛过远", signature(stops), "跳过该候选");
                continue;
            }
            String signature = signature(stops);
            if (!signatures.add(signature)) {
                continue;
            }

            Map<String, Object> route;
            try {
                route = routeEstimateTool.route(planId, routeStops);
                route.put("includesOrigin", origin != null);
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
            if (!hasExplicitEnd(intent) && isTooShort(intent, totalMinutes)) {
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
            enrichCardContent(sorted.get(index), intent, newRank);
        }
        return sorted;
    }

    private List<Poi> mergeExplicitPoiCandidates(Map<String, Object> intent, List<Poi> candidates) {
        List<Poi> merged = new ArrayList<>(candidates == null ? List.of() : candidates);
        List<Map<String, Object>> explicitPois = castList(intent.get("explicitPois"));
        if (explicitPois.isEmpty()) {
            return merged;
        }
        double[] base = explicitBasePoint(intent, merged);
        int index = 0;
        for (Map<String, Object> item : explicitPois) {
            String name = String.valueOf(item.getOrDefault("name", "")).trim();
            if (name.isBlank() || merged.stream().anyMatch(poi -> poi.getName().equals(name))) {
                continue;
            }
            PoiType type = explicitPoiType(item);
            Poi matched = bestExplicitMatch(name, type, merged);
            if (matched != null) {
                merged.add(renamePoi(matched, name, type));
                continue;
            }
            if (!allowMockPoi) {
                continue;
            }
            double lng = base[0] + index * 0.004;
            double lat = base[1] + index * 0.003;
            merged.add(new Poi(name, type, explicitSubtype(type), "用户指定地点，演示环境按星海广场周边映射",
                    lng, lat, defaultExplicitDuration(type), defaultExplicitPrice(type), 4.6,
                    false, type == PoiType.DINING && name.contains("清淡"), true, true,
                    type != PoiType.DINING && name.contains("无票"), type == PoiType.DINING && name.contains("满"),
                    "user-specified-mock", "explicit-" + index));
            index++;
        }
        return merged;
    }

    private boolean hasCompleteExplicitPoiRoute(Map<String, Object> intent) {
        List<Map<String, Object>> explicitPois = castList(intent.get("explicitPois"));
        boolean hasActivity = explicitPois.stream().anyMatch(item -> explicitPoiType(item) != PoiType.DINING && explicitPoiType(item) != PoiType.EXTRA);
        boolean hasDining = explicitPois.stream().anyMatch(item -> explicitPoiType(item) == PoiType.DINING);
        boolean hasExtra = explicitPois.stream().anyMatch(item -> explicitPoiType(item) == PoiType.EXTRA);
        return explicitPois.size() >= 3 && hasActivity && hasDining && hasExtra;
    }

    private List<Map<String, Object>> buildExplicitPoiOptions(UUID planId, Map<String, Object> intent,
                                                              List<Poi> candidates, Map<String, Object> weather) {
        List<Poi> explicitStops = explicitStops(intent, candidates);
        if (explicitStops.size() < 3 || !hasRequiredPoiCoverage(explicitStops)) {
            throw new PlanBlockedException(planId, "amap",
                    "抱歉，未能核验你指定的全部地点，请检查 POI 名称或放宽为附近同类型地点。", 422);
        }
        List<Map<String, Object>> options = new ArrayList<>();
        Poi origin = originPoi(intent);
        for (int variant = 1; variant <= 3; variant++) {
            List<Poi> stops = adjustExplicitDurations(explicitStops, variant);
            List<Poi> routeStops = routeStops(origin, stops);
            Map<String, Object> route = routeEstimateTool.route(planId, routeStops);
            route.put("includesOrigin", origin != null);
            route.put("transportStrategy", explicitTransportStrategy(variant));
            route = normalizeExplicitRoute(route, routeStops, variant);
            int totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
            boolean timeCompressed = false;
            if (!fitsDuration(intent, totalMinutes)) {
                tools.recovery(planId, "时间冲突", signature(stops), "压缩指定站点停留时间");
                stops = fitExplicitDurationsToWindow(explicitStops, variant,
                        ((Number) route.get("travelMinutes")).intValue(), durationMinutes(intent));
                totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
                timeCompressed = true;
            }
            Map<String, Object> option = option(variant, stops, route, totalMinutes, intent, weather);
            applyExplicitVariant(option, variant);
            if (timeCompressed) {
                option.put("constraintRecoveries", List.of(Map.of(
                        "code", "TIME_CONFLICT",
                        "status", "RECOVERED",
                        "reason", "用户指定地点全部保留，已压缩停留时间并优先保障硬结束时间。",
                        "fallbackTarget", "压缩停留时间/缩短咖啡停留/优先叫车收尾",
                        "source", "mock"
                )));
            }
            options.add(option);
        }
        return options;
    }

    private List<Poi> explicitStops(Map<String, Object> intent, List<Poi> candidates) {
        List<Poi> stops = new ArrayList<>();
        for (Map<String, Object> item : castList(intent.get("explicitPois"))) {
            String name = String.valueOf(item.getOrDefault("name", "")).trim();
            if (name.isBlank() || stops.stream().anyMatch(poi -> poi.getName().equals(name))) {
                continue;
            }
            PoiType type = explicitPoiType(item);
            candidates.stream()
                    .filter(poi -> poi.getName().equals(name))
                    .findFirst()
                    .ifPresent(stops::add);
        }
        return stops;
    }

    private List<Poi> adjustExplicitDurations(List<Poi> stops, int variant) {
        return stops.stream()
                .map(poi -> poiWithDuration(poi, explicitDurationForVariant(poi.getType(), variant)))
                .toList();
    }

    private List<Poi> fitExplicitDurationsToWindow(List<Poi> stops, int variant, int travelMinutes, int windowMinutes) {
        if (windowMinutes <= 0 || stops.isEmpty()) {
            return adjustExplicitDurations(stops, Math.max(variant, 3));
        }
        int available = Math.max(90, windowMinutes - Math.max(0, travelMinutes));
        List<Poi> compact = adjustExplicitDurations(stops, 3);
        int compactStay = compact.stream().mapToInt(Poi::getDurationMinutes).sum();
        if (compactStay <= available) {
            return compact;
        }
        return compact.stream()
                .map(poi -> {
                    int minStay = poi.getType() == PoiType.DINING ? 35 : poi.getType() == PoiType.EXTRA ? 15 : 35;
                    int adjusted = Math.max(minStay,
                            (int) Math.floor(poi.getDurationMinutes() * (available / (double) compactStay)));
                    return poiWithDuration(poi, adjusted);
                })
                .toList();
    }

    private int explicitDurationForVariant(PoiType type, int variant) {
        if (variant == 1) {
            return type == PoiType.DINING ? 60 : type == PoiType.EXTRA ? 30 : 55;
        }
        if (variant == 2) {
            return type == PoiType.DINING ? 55 : type == PoiType.EXTRA ? 25 : 50;
        }
        return type == PoiType.DINING ? 45 : type == PoiType.EXTRA ? 20 : 40;
    }

    private Map<String, Object> normalizeExplicitRoute(Map<String, Object> route, List<Poi> routeStops, int variant) {
        Map<String, Object> normalized = new LinkedHashMap<>(route);
        List<Integer> segmentMinutes = new ArrayList<>();
        List<Double> segmentDistancesKm = new ArrayList<>();
        List<Map<String, Object>> segments = new ArrayList<>();
        List<String> routeModes = new ArrayList<>();
        double distance = 0.0;
        int travel = 0;
        String mode = explicitRouteMode(variant);
        for (int i = 1; i < routeStops.size(); i++) {
            Poi from = routeStops.get(i - 1);
            Poi to = routeStops.get(i);
            double segmentKm = Math.max(0.2, distanceKm(from.getLat(), from.getLng(), to.getLat(), to.getLng()));
            double roundedSegment = Math.round(segmentKm * 10.0) / 10.0;
            int minutes = explicitSegmentMinutes(segmentKm, variant);
            distance += segmentKm;
            travel += minutes;
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
        normalized.put("travelMinutes", travel);
        normalized.put("distanceKm", Math.round(distance * 10.0) / 10.0);
        normalized.put("segmentMinutes", segmentMinutes);
        normalized.put("segmentDistancesKm", segmentDistancesKm);
        normalized.put("segments", segments);
        normalized.put("routeModes", routeModes);
        normalized.put("source", String.valueOf(route.getOrDefault("source", "")) + "+explicit_strategy");
        return normalized;
    }

    private int explicitSegmentMinutes(double distanceKm, int variant) {
        double minutesPerKm = switch (variant) {
            case 1 -> 12.0;
            case 2 -> 7.5;
            default -> 5.0;
        };
        int buffer = variant == 1 ? 2 : 1;
        return Math.max(3, (int) Math.ceil(distanceKm * minutesPerKm) + buffer);
    }

    private String explicitRouteMode(int variant) {
        return switch (variant) {
            case 1 -> "walking";
            case 2 -> "walking_ride_hailing";
            default -> "ride_hailing";
        };
    }

    private void applyExplicitVariant(Map<String, Object> option, int variant) {
        Map<String, Object> route = castMap(option.get("route"));
        String routeShape = routeShape(stopName(castList(option.get("timeline")), 0),
                String.valueOf(option.getOrDefault("diningName", "")),
                stopName(castList(option.get("timeline")), castList(option.get("timeline")).size() - 1));
        if (variant == 1) {
            option.put("name", "温柔步行遛弯版");
            option.put("tag", "步行慢逛");
            option.put("tagline", routeShape + "，全程步行为主，展览和咖啡都留缓冲，餐厅优先靠窗位。");
            option.put("executionList", List.of("普通门票", "靠窗座位", "回家快车预约", "天气提醒"));
            option.put("fitReasons", List.of("严格保留你指定的 POI，不替换餐厅和展馆。", "节奏最松，适合按 14:00-18:00 慢慢走完。", "预定细节：普通票、靠窗位、快车回家。"));
            option.put("riskNotes", List.of("步行时间受天气影响，出发前建议看一眼降雨和风力。", "餐厅靠窗位为 Mock 预定偏好，真实情况以商家确认为准。"));
        } else if (variant == 2) {
            option.put("name", "轻松短驳舒适版");
            option.put("tag", "短驳省力");
            option.put("tagline", routeShape + "，近距离步行加一段短驳，餐厅靠里安静位，减少赶路压力。");
            option.put("executionList", List.of("普通门票", "靠里安静座", "短驳打车", "提前取号"));
            option.put("fitReasons", List.of("指定地点顺序不变，只把交通方式改成步行加短驳。", "吃饭前预留取号时间，避免到店等位太久。", "预定细节：靠里安静位、短驳车、提前取号提醒。"));
            option.put("riskNotes", List.of("短驳路段高峰期可能慢 5-10 分钟。", "如果餐厅满员，执行中心会走同名/同区域替代处理。"));
        } else {
            option.put("name", "高效快享安心版");
            option.put("tag", "高效快享");
            option.put("tagline", routeShape + "，压缩停留并用快车收尾，门票走快速通道，适合 18:00 前稳妥到家。");
            option.put("executionList", List.of("快速通道门票", "VIP/优先座", "回家快车", "排队提醒"));
            option.put("fitReasons", List.of("保留大连世界博览广场、海味当家和咖啡点，不用换店凑方案。", "时间分配最紧凑，优先保证 18:00 前回家。", "预定细节：快速通道、VIP/优先座、快车回家。"));
            option.put("riskNotes", List.of("节奏较紧，展览或用餐延时会压缩咖啡时间。", "快速通道和 VIP 座位为 Mock 演示能力，真实执行需商家接口确认。"));
        }
        route.put("transportStrategy", explicitTransportStrategy(variant));
        option.put("route", route);
        option.put("routeHighlights", List.of(routeShape, String.valueOf(route.get("transportStrategy")),
                String.join("，", castStringList(option.get("executionList")))));
        option.put("routeSummary", option.get("tagline"));
    }

    private String explicitTransportStrategy(int variant) {
        return switch (variant) {
            case 1 -> "全程步行";
            case 2 -> "步行 + 短驳打车";
            default -> "高效快车收尾";
        };
    }

    private Poi bestExplicitMatch(String name, PoiType type, List<Poi> candidates) {
        String normalizedName = normalizePoiName(name);
        return candidates.stream()
                .filter(poi -> poi.getType() == type)
                .filter(poi -> {
                    String candidateName = normalizePoiName(poi.getName());
                    return normalizedName.contains(candidateName) || candidateName.contains(normalizedName);
                })
                .findFirst()
                .orElse(null);
    }

    private String normalizePoiName(String name) {
        return name == null ? "" : name.replaceAll("[\\s()（）・·\\-]", "");
    }

    private Poi renamePoi(Poi original, String name, PoiType type) {
        return new Poi(name, type, original.getSubtype(), original.getAddress(), original.getLng(), original.getLat(),
                original.getDurationMinutes(), original.getAvgPrice(), original.getRating(), original.isKidFriendly(),
                original.isLowCalorie(), original.isIndoor(), original.isSocial(), original.isTicketProblem(),
                original.isSeatProblem(), original.getSourceProvider(), original.getSourcePoiId());
    }

    private PoiType explicitPoiType(Map<String, Object> item) {
        String type = String.valueOf(item.getOrDefault("type", ""));
        if ("dining".equals(type) || "餐饮".equals(type)) return PoiType.DINING;
        if ("extra".equals(type) || "补充".equals(type)) return PoiType.EXTRA;
        if ("culture".equals(type) || String.valueOf(item.getOrDefault("name", "")).contains("博览")) return PoiType.CULTURE;
        return PoiType.ENTERTAINMENT;
    }

    private String explicitSubtype(PoiType type) {
        return type == PoiType.DINING ? "用户指定餐厅" : type == PoiType.EXTRA ? "用户指定补充点" : "用户指定活动";
    }

    private int defaultExplicitDuration(PoiType type) {
        return type == PoiType.DINING ? 65 : type == PoiType.EXTRA ? 30 : 60;
    }

    private int defaultExplicitPrice(PoiType type) {
        return type == PoiType.DINING ? 120 : type == PoiType.EXTRA ? 35 : 60;
    }

    private double[] explicitBasePoint(Map<String, Object> intent, List<Poi> candidates) {
        double[] anchor = anchor(intent);
        if (anchor != null) {
            return anchor;
        }
        if (candidates != null && !candidates.isEmpty()) {
            return new double[] {candidates.get(0).getLng(), candidates.get(0).getLat()};
        }
        return new double[] {121.588, 38.883};
    }

    private boolean hasRequiredPoiCoverage(List<Poi> stops) {
        boolean hasDining = stops.stream().anyMatch(poi -> poi.getType() == PoiType.DINING);
        boolean hasActivity = stops.stream().anyMatch(poi -> poi.getType() == PoiType.ENTERTAINMENT || poi.getType() == PoiType.CULTURE);
        return stops.size() >= 3 && hasDining && hasActivity;
    }

    private List<Poi> routeStops(Poi origin, List<Poi> stops) {
        if (origin == null) {
            return stops;
        }
        List<Poi> routeStops = new ArrayList<>();
        routeStops.add(origin);
        routeStops.addAll(stops);
        return routeStops;
    }

    private Poi originPoi(Map<String, Object> intent) {
        double[] anchor = anchor(intent);
        if (anchor == null) {
            return null;
        }
        Map<String, Object> location = castMap(intent.get("location"));
        String name = String.valueOf(location.getOrDefault("name",
                location.getOrDefault("district", location.getOrDefault("city", "出发地"))));
        if (name.isBlank() || "null".equals(name)) {
            name = "出发地";
        }
        String address = String.valueOf(location.getOrDefault("address", name));
        return new Poi(name, PoiType.EXTRA, "出发地", address, anchor[0], anchor[1],
                0, 0, 0, false, false, true, false, false, false);
    }

    private List<Poi> optimizeStopOrder(Poi origin, List<Poi> stops) {
        if (stops.size() <= 3) {
            return stops;
        }
        List<Poi> nonDining = new ArrayList<>(stops.stream()
                .filter(poi -> poi.getType() != PoiType.DINING)
                .toList());
        List<Poi> dining = new ArrayList<>(stops.stream()
                .filter(poi -> poi.getType() == PoiType.DINING)
                .toList());
        if (nonDining.isEmpty() || dining.isEmpty()) {
            return stops;
        }
        List<Poi> ordered = new ArrayList<>();
        Poi cursor = origin == null ? nearestToCentroid(nonDining) : origin;
        while (!nonDining.isEmpty()) {
            Poi next = nearest(cursor, nonDining);
            ordered.add(next);
            nonDining.remove(next);
            cursor = next;
            if (ordered.size() >= Math.max(1, stops.size() - dining.size())) {
                break;
            }
        }
        Poi diningCursor = cursor;
        ordered.addAll(dining.stream()
                .sorted(Comparator.comparingDouble(poi -> distanceKm(diningCursor.getLat(), diningCursor.getLng(), poi.getLat(), poi.getLng())))
                .toList());
        return ordered;
    }

    private Poi nearest(Poi from, List<Poi> candidates) {
        return candidates.stream()
                .min(Comparator.comparingDouble(poi -> distanceKm(from.getLat(), from.getLng(), poi.getLat(), poi.getLng())))
                .orElse(candidates.get(0));
    }

    private Poi nearestToCentroid(List<Poi> candidates) {
        double avgLat = candidates.stream().mapToDouble(Poi::getLat).average().orElse(candidates.get(0).getLat());
        double avgLng = candidates.stream().mapToDouble(Poi::getLng).average().orElse(candidates.get(0).getLng());
        return candidates.stream()
                .min(Comparator.comparingDouble(poi -> distanceKm(avgLat, avgLng, poi.getLat(), poi.getLng())))
                .orElse(candidates.get(0));
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
        if (hasExplicitEnd(intent)) {
            return totalMinutes <= duration;
        }
        int tolerance = Math.max(30, duration / 5);
        return totalMinutes >= duration - tolerance && totalMinutes <= duration + tolerance;
    }

    private boolean hasExplicitEnd(Map<String, Object> intent) {
        Map<String, Object> timeWindow = castMap(intent.get("time_window"));
        return !String.valueOf(timeWindow.getOrDefault("end", "")).isBlank()
                && !"null".equals(String.valueOf(timeWindow.get("end")));
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
                original.isSeatProblem(),
                original.getSourceProvider(),
                original.getSourcePoiId()
        );
    }

    private Map<String, Object> option(int rank, List<Poi> stops, Map<String, Object> route, int totalMinutes,
                                       Map<String, Object> intent, Map<String, Object> weather) {
        // 从 route 中读取分段时间，用于准确计算每站开始时间
        List<Integer> segmentMinutes = castSegmentMinutes(route.get("segmentMinutes"));
        LocalTime currentTime = parseStartTime(intent);

        LocalTime departureTime = currentTime;
        boolean includesOrigin = Boolean.TRUE.equals(route.get("includesOrigin"));
        if (includesOrigin && !segmentMinutes.isEmpty()) {
            currentTime = currentTime.plusMinutes(segmentMinutes.get(0));
        }
        route.put("departureTime", departureTime.toString());

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Poi poi = stops.get(i);
            Map<String, Object> timelineItem = new LinkedHashMap<>();
            timelineItem.put("time", currentTime.toString());
            timelineItem.put("type", poi.getType() == PoiType.DINING ? "餐饮" : poi.getType() == PoiType.EXTRA ? "补充" : "活动");
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
            timelineItem.put("sourceProvider", poi.getSourceProvider());
            timelineItem.put("sourcePoiId", poi.getSourcePoiId());
            timeline.add(timelineItem);
            // 累加当前站点停留时间 + 到下一站的交通时间
            currentTime = currentTime.plusMinutes(poi.getDurationMinutes());
            int nextRouteSegmentIndex = includesOrigin ? i + 1 : i;
            if (nextRouteSegmentIndex < segmentMinutes.size()) {
                currentTime = currentTime.plusMinutes(segmentMinutes.get(nextRouteSegmentIndex));
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

    private void enrichCardContent(Map<String, Object> option, Map<String, Object> intent, int rank) {
        List<Map<String, Object>> timeline = castList(option.get("timeline"));
        Map<String, Object> route = castMap(option.get("route"));
        List<Map<String, Object>> segments = castList(route.get("segments"));
        String firstStop = stopName(timeline, 0);
        String diningStop = timeline.stream()
                .filter(stop -> "餐饮".equals(String.valueOf(stop.get("type"))))
                .map(stop -> stopName(stop))
                .filter(name -> !name.isBlank())
                .findFirst()
                .orElse(stopName(timeline, Math.min(1, timeline.size() - 1)));
        String lastStop = stopName(timeline, timeline.size() - 1);
        String routeShape = routeShape(firstStop, diningStop, lastStop);
        double distanceKm = doubleValue(route.get("distanceKm"));
        int travelMinutes = intValue(route.get("travelMinutes")) == null ? 0 : intValue(route.get("travelMinutes"));
        int totalMinutes = intValue(option.get("totalMinutes")) == null ? 0 : intValue(option.get("totalMinutes"));
        int budget = intValue(option.get("budgetEstimate")) == null ? 0 : intValue(option.get("budgetEstimate"));
        String scenario = String.valueOf(intent.getOrDefault("scenario", ""));
        boolean family = "family".equals(scenario) || timeline.toString().contains("亲子") || timeline.toString().contains("孩子");
        boolean couple = "couple".equals(scenario);
        boolean friends = "friends".equals(scenario) || timeline.toString().contains("朋友");
        boolean lowCalorie = timeline.toString().contains("轻食") || timeline.toString().contains("低卡") || timeline.toString().contains("健康");
        boolean indoor = timeline.stream().anyMatch(stop -> Boolean.TRUE.equals(stop.get("indoor")));

        option.put("name", cardName(routeShape, firstStop, diningStop, distanceKm, totalMinutes, budget, family, couple, friends, lowCalorie, rank));
        option.put("tagline", cardTagline(routeShape, segments, distanceKm, travelMinutes, totalMinutes, budget, family, couple, friends, lowCalorie));
        option.put("tag", cardTag(family, couple, friends, lowCalorie, indoor, distanceKm, travelMinutes));
        option.put("routeSummary", routeSummary(routeShape, segments, distanceKm, travelMinutes, totalMinutes, budget));
        option.put("routeHighlights", routeHighlights(timeline, segments, distanceKm, travelMinutes));
        option.put("fitReasons", fitReasons(timeline, segments, intent, distanceKm, travelMinutes, totalMinutes, budget, family, lowCalorie));
        option.put("riskNotes", riskNotes(option, segments));
    }

    private String cardName(String routeShape, String firstStop, String diningStop, double distanceKm,
                            int totalMinutes, int budget, boolean family, boolean couple, boolean friends,
                            boolean lowCalorie, int rank) {
        if (family && lowCalorie) {
            return "亲子轻食顺路局";
        }
        if (family) {
            return shortName(firstStop) + "亲子慢玩线";
        }
        if (couple && distanceKm <= 4.0) {
            return shortName(firstStop) + "微醺散步线";
        }
        if (friends && diningStop.contains("烤")) {
            return "先玩后烤肉小聚线";
        }
        if (friends) {
            return shortName(firstStop) + "朋友碰头线";
        }
        if (lowCalorie) {
            return "轻负担逛吃线";
        }
        if (totalMinutes > 0 && totalMinutes <= 210 && distanceKm <= 5.0) {
            return "三小时近场收口线";
        }
        if (budget > 0 && budget <= 300) {
            return "省预算顺路逛吃线";
        }
        if (!routeShape.isBlank()) {
            return shortName(routeShape) + "顺游线";
        }
        return "本地短途精选线 " + rank;
    }

    private String cardTagline(String routeShape, List<Map<String, Object>> segments, double distanceKm,
                               int travelMinutes, int totalMinutes, int budget, boolean family, boolean couple,
                               boolean friends, boolean lowCalorie) {
        List<String> parts = new ArrayList<>();
        if (!routeShape.isBlank()) {
            parts.add(routeShape + "一路接上");
        }
        if (distanceKm > 0 && travelMinutes > 0) {
            parts.add(formatDistance(distanceKm) + "路程压在" + travelMinutes + "分钟左右");
        }
        if (budget > 0) {
            parts.add("预算约" + budget + "元");
        }
        if (family) {
            parts.add("给孩子留了停留时间");
        } else if (couple) {
            parts.add("节奏不赶，适合边走边聊");
        } else if (friends) {
            parts.add("碰头、玩乐、吃饭都好解释");
        } else if (lowCalorie) {
            parts.add("吃饭负担更轻");
        }
        if (segments.size() >= 3) {
            parts.add("点位多但不绕");
        }
        return String.join("，", parts);
    }

    private String cardTag(boolean family, boolean couple, boolean friends, boolean lowCalorie,
                           boolean indoor, double distanceKm, int travelMinutes) {
        if (family) return "亲子友好";
        if (lowCalorie) return "轻食友好";
        if (distanceKm > 0 && distanceKm <= 3.0) return "近场顺路";
        if (travelMinutes > 0 && travelMinutes <= 25) return "少折腾";
        if (indoor) return "室内稳妥";
        if (couple) return "约会氛围";
        if (friends) return "朋友小聚";
        return "路线顺路";
    }

    private String routeSummary(String routeShape, List<Map<String, Object>> segments, double distanceKm,
                                int travelMinutes, int totalMinutes, int budget) {
        StringBuilder text = new StringBuilder();
        if (!routeShape.isBlank()) {
            text.append(routeShape).append("，按地图顺序走");
        } else {
            text.append("已按地图路线重排点位");
        }
        if (distanceKm > 0 || travelMinutes > 0) {
            text.append("；");
            if (distanceKm > 0) text.append("全程约").append(formatDistance(distanceKm));
            if (distanceKm > 0 && travelMinutes > 0) text.append("，");
            if (travelMinutes > 0) text.append("交通约").append(travelMinutes).append("分钟");
        }
        if (totalMinutes > 0) {
            text.append("，整体约").append(formatHoursText(totalMinutes));
        }
        if (budget > 0) {
            text.append("，预算约").append(budget).append("元");
        }
        if (!segments.isEmpty()) {
            Map<String, Object> longest = segments.stream()
                    .max(Comparator.comparingDouble(segment -> doubleValue(segment.get("distanceKm"))))
                    .orElse(Map.of());
            if (!longest.isEmpty()) {
                text.append("。最长一段是")
                        .append(shortName(String.valueOf(longest.getOrDefault("from", ""))))
                        .append("到")
                        .append(shortName(String.valueOf(longest.getOrDefault("to", ""))))
                        .append("，约")
                        .append(formatDistance(doubleValue(longest.get("distanceKm"))))
                        .append("。");
            }
        }
        return text.toString();
    }

    private List<String> routeHighlights(List<Map<String, Object>> timeline, List<Map<String, Object>> segments,
                                         double distanceKm, int travelMinutes) {
        List<String> highlights = new ArrayList<>();
        if (!segments.isEmpty()) {
            highlights.add("路线顺序：" + segments.stream()
                    .map(segment -> shortName(String.valueOf(segment.getOrDefault("to", ""))))
                    .filter(name -> !name.isBlank() && !"出发地".equals(name))
                    .distinct()
                    .reduce((a, b) -> a + " → " + b)
                    .orElse(compactNames(timeline)));
        } else {
            highlights.add("路线顺序：" + compactNames(timeline));
        }
        if (distanceKm > 0 && travelMinutes > 0) {
            highlights.add("地图估算：" + formatDistance(distanceKm) + "，交通约" + travelMinutes + "分钟");
        }
        String dining = timeline.stream()
                .filter(stop -> "餐饮".equals(String.valueOf(stop.get("type"))))
                .map(this::stopName)
                .findFirst()
                .orElse("");
        if (!dining.isBlank()) {
            highlights.add(shortName(dining) + "放在中后段，玩完再吃饭更顺。");
        }
        return highlights;
    }

    private List<String> fitReasons(List<Map<String, Object>> timeline, List<Map<String, Object>> segments,
                                    Map<String, Object> intent, double distanceKm, int travelMinutes,
                                    int totalMinutes, int budget, boolean family, boolean lowCalorie) {
        List<String> reasons = new ArrayList<>();
        reasons.add(routeSummary(routeShape(stopName(timeline, 0),
                timeline.stream().filter(stop -> "餐饮".equals(String.valueOf(stop.get("type")))).map(this::stopName).findFirst().orElse(""),
                stopName(timeline, timeline.size() - 1)), segments, distanceKm, travelMinutes, totalMinutes, budget));
        if (family) {
            reasons.add("亲子点位和吃饭点没有拆得太散，孩子中途累了也容易压缩最后一站。");
        } else if (lowCalorie) {
            reasons.add("餐饮选择偏轻负担，适合把主要精力留给活动，不用为吃饭绕远。");
        } else {
            reasons.add("活动、餐饮、补充点按地图动线串起来，同行人看卡片就能理解怎么走。");
        }
        if (!segments.isEmpty()) {
            reasons.add("每段路线都来自右侧地图同一份规划数据，点位和顺序保持一致。");
        }
        return reasons;
    }

    private List<String> riskNotes(Map<String, Object> option, List<Map<String, Object>> segments) {
        List<String> notes = new ArrayList<>(castStringList(option.get("riskNotes")));
        if (notes.isEmpty()) {
            notes.add("商家营业、排队和票务状态出发前还需要再确认一次。");
        }
        if (segments.stream().anyMatch(segment -> "driving".equals(String.valueOf(segment.get("routeMode"))))) {
            notes.add("含打车或驾车路段，高峰期建议预留一点缓冲。");
        }
        return notes;
    }

    private String routeShape(String firstStop, String diningStop, String lastStop) {
        List<String> names = new ArrayList<>();
        if (firstStop != null && !firstStop.isBlank()) names.add(shortName(firstStop));
        if (diningStop != null && !diningStop.isBlank() && names.stream().noneMatch(diningStop::contains)) {
            names.add(shortName(diningStop));
        }
        if (lastStop != null && !lastStop.isBlank() && names.stream().noneMatch(lastStop::contains)) {
            names.add(shortName(lastStop));
        }
        return String.join(" → ", names);
    }

    private String compactNames(List<Map<String, Object>> timeline) {
        return timeline.stream()
                .map(this::stopName)
                .filter(name -> !name.isBlank())
                .map(this::shortName)
                .distinct()
                .reduce((a, b) -> a + " → " + b)
                .orElse("");
    }

    private String stopName(List<Map<String, Object>> timeline, int index) {
        if (timeline.isEmpty() || index < 0 || index >= timeline.size()) {
            return "";
        }
        return stopName(timeline.get(index));
    }

    private String stopName(Map<String, Object> stop) {
        return String.valueOf(stop.getOrDefault("name", "")).trim();
    }

    private String shortName(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.isBlank() || "null".equals(raw)) {
            return "";
        }
        String base = raw.replaceAll("（[^）]+）|\\([^)]+\\)$", "")
                .replaceAll("(旗舰店|体验店|官方店|专门店|主题店){2,}", "$1");
        if (base.length() > 10) {
            base = base.replaceAll("(餐厅|饭店|美食|小吃|料理|烤肉|烧烤|火锅|咖啡|影院|影城|公园|广场|乐园|书店|商场|中心).*$", "$1");
        }
        return base.length() > 12 ? base.substring(0, 12) : base;
    }

    private String formatDistance(double distanceKm) {
        if (distanceKm <= 0) {
            return "距离待估";
        }
        if (distanceKm < 1.0) {
            return Math.round(distanceKm * 1000) + "m";
        }
        return (Math.round(distanceKm * 10.0) / 10.0) + "km";
    }

    private String formatHoursText(int minutes) {
        if (minutes <= 0) {
            return "时长待估";
        }
        if (minutes < 60) {
            return minutes + "分钟";
        }
        double hours = Math.round(minutes / 6.0) / 10.0;
        return hours + "小时";
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
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

    private void addPoiDetailWarnings(UUID planId, List<Map<String, Object>> options, List<Map<String, Object>> warnings) {
        List<String> conflicts = new ArrayList<>();
        int checked = 0;
        int skipped = 0;
        for (Map<String, Object> option : options.stream().limit(3).toList()) {
            for (Map<String, Object> stop : castList(option.get("timeline"))) {
                if (checked >= 9) {
                    break;
                }
                String sourcePoiId = String.valueOf(stop.getOrDefault("sourcePoiId", ""));
                if (sourcePoiId.isBlank() || "null".equals(sourcePoiId)) {
                    skipped++;
                    continue;
                }
                checked++;
                Map<String, Object> detail = poiDetailTool.fetchDetail(planId, sourcePoiId,
                        String.valueOf(stop.getOrDefault("name", "")));
                LocalTime time = parseStopTime(stop.get("time"));
                if (poiDetailTool.isClosedAt(detail, time)) {
                    conflicts.add(String.valueOf(stop.getOrDefault("name", sourcePoiId)) + " 在 "
                            + stop.getOrDefault("time", "") + " 可能未营业");
                }
            }
        }
        if (!conflicts.isEmpty()) {
            warnings.add(warning("营业时间风险", String.join("；", conflicts)));
        } else if (checked == 0 && skipped > 0) {
            warnings.add(warning("营业状态待确认", "当前候选地点缺少可核验的高德 POI ID，出发前建议再次确认营业时间。"));
        }
    }

    private LocalTime parseStopTime(Object value) {
        try {
            return LocalTime.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
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
                                                  Map<String, Object> execution, String currentView, String currentStep,
                                                  Integer selectedRank) {
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
        if (selectedRank != null) {
            payload.put("selectedRank", selectedRank);
        }
        if (!execution.isEmpty()) {
            payload.put("executionSteps", executionSteps(execution));
        }
        return payload;
    }

    private Map<String, Object> confirmPayload(PlanSession session, Map<String, Object> execution, int selectedRank) {
        Map<String, Object> intent = fromJson(session.getIntentJson());
        Map<String, Object> result = fromJson(session.getResultJson());
        return planResultPayload(session.getId(), intent, result, execution, "collab", "plans", selectedRank);
    }

    private List<Map<String, Object>> executionSteps(Map<String, Object> execution) {
        List<Map<String, Object>> structured = castList(execution.get("executionSteps"));
        if (!structured.isEmpty()) {
            return structured;
        }
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
