package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningService {
    private final PlanSessionRepository planSessionRepository;
    private final PlanOptionRepository planOptionRepository;
    private final FeedbackEventRepository feedbackEventRepository;
    private final ToolCallLogRepository toolCallLogRepository;
    private final MockTools tools;
    private final AmapPoiSearchTool poiSearchTool;
    private final AmapRouteEstimateTool routeEstimateTool;
    private final SearchVerifierAgent searchVerifierAgent;
    private final IntentParserAgent intentParserAgent;
    private final PlanGeneratorAgent planGeneratorAgent;
    private final PlanValidationService planValidationService;
    private final PromptCatalog promptCatalog;
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
                           PlanGeneratorAgent planGeneratorAgent,
                           PlanValidationService planValidationService,
                           PromptCatalog promptCatalog,
                           ObjectMapper objectMapper,
                           @org.springframework.beans.factory.annotation.Value("${app.allow-mock-poi:false}") boolean allowMockPoi) {
        this.planSessionRepository = planSessionRepository;
        this.planOptionRepository = planOptionRepository;
        this.feedbackEventRepository = feedbackEventRepository;
        this.toolCallLogRepository = toolCallLogRepository;
        this.tools = tools;
        this.poiSearchTool = poiSearchTool;
        this.routeEstimateTool = routeEstimateTool;
        this.searchVerifierAgent = searchVerifierAgent;
        this.intentParserAgent = intentParserAgent;
        this.planGeneratorAgent = planGeneratorAgent;
        this.planValidationService = planValidationService;
        this.promptCatalog = promptCatalog;
        this.objectMapper = objectMapper;
        this.allowMockPoi = allowMockPoi;
    }

    @Transactional
    public PlanResponse createPlan(String token, String message) {
        PlanSession session = planSessionRepository.save(PlanSession.create(token, message));
        Map<String, Object> intent = intentParserAgent.parse(session.getId(), message);
        List<Map<String, Object>> webEvidence = searchVerifierAgent.verify(session.getId(), intent, message);
        List<Poi> candidates = poiSearchTool.searchPois(session.getId(), intent);
        List<Map<String, Object>> options = allowMockPoi
                ? buildOptions(session.getId(), intent, candidates)
                : planGeneratorAgent.generate(session.getId(), intent, buildRouteCandidates(session.getId(), intent, candidates), webEvidence);
        planValidationService.validate(session.getId(), options, candidates);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("options", options);
        result.put("promptModules", promptCatalog.loadPrompts().keySet());
        result.put("agentPipeline", promptCatalog.loadPipeline());
        result.put("webEvidenceCount", webEvidence.size());
        session.markReady(toJson(intent), toJson(result));
        planSessionRepository.save(session);
        for (Map<String, Object> option : options) {
            planOptionRepository.save(new PlanOption(session.getId(), ((Number) option.get("rank")).intValue(), toJson(option)));
        }
        return toResponse(session.getId());
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
            throw new IllegalStateException("Plan is not ready to confirm");
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

    @Transactional
    public PlanResponse feedback(UUID id, String message) {
        PlanSession previous = planSessionRepository.findById(id).orElseThrow();
        feedbackEventRepository.save(new FeedbackEvent(id, message));
        return createPlan(previous.getSessionToken(), previous.getRawInput() + ". feedback: " + message);
    }

    Map<String, Object> analyzeIntent(String message) {
        return intentParserAgent.keywordFallback(message);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildRouteCandidates(UUID planId, Map<String, Object> intent, List<Poi> candidates) {
        List<Poi> activities = candidates.stream()
                .filter(poi -> poi.getType() == PoiType.ENTERTAINMENT || poi.getType() == PoiType.CULTURE)
                .sorted(Comparator.comparing(Poi::getRating).reversed())
                .toList();
        List<Poi> dining = candidates.stream()
                .filter(poi -> poi.getType() == PoiType.DINING)
                .sorted(Comparator.comparing(Poi::getRating).reversed())
                .toList();
        List<Poi> extras = candidates.stream()
                .filter(poi -> poi.getType() == PoiType.EXTRA)
                .sorted(Comparator.comparing(Poi::getRating).reversed())
                .toList();
        if (extras.isEmpty()) {
            extras = activities.stream().skip(Math.min(1, activities.size())).toList();
        }
        if (activities.isEmpty() || dining.isEmpty() || extras.isEmpty()) {
            throw new PlanBlockedException(planId, "amap", BlockMessages.NO_POI_FOUND, 422);
        }

        List<Map<String, Object>> routeCandidates = new ArrayList<>();
        int groupTotal = groupTotal(intent);
        int count = Math.min(3, Math.min(Math.min(activities.size(), dining.size()), extras.size()));
        for (int i = 0; i < count; i++) {
            List<Poi> stops = List.of(
                    activities.get(i % activities.size()),
                    dining.get(i % dining.size()),
                    extras.get(i % extras.size())
            );
            Map<String, Object> route = routeEstimateTool.route(planId, stops);
            int travelMinutes = ((Number) route.getOrDefault("travelMinutes", 0)).intValue();
            int totalMinutes = stops.stream().mapToInt(Poi::getDurationMinutes).sum() + travelMinutes;
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("candidateIndex", i + 1);
            candidate.put("timeline", timelineFromStops(stops));
            candidate.put("route", route);
            candidate.put("totalMinutes", totalMinutes);
            candidate.put("budgetEstimate", stops.stream().mapToInt(Poi::getAvgPrice).sum() * groupTotal);
            candidate.put("firstStop", stops.get(0).getName());
            candidate.put("diningName", stops.get(1).getName());
            candidate.put("lastStop", stops.get(2).getName());
            routeCandidates.add(candidate);
        }
        if (routeCandidates.size() < 3) {
            throw new PlanBlockedException(planId, "amap", BlockMessages.NO_POI_FOUND, 422);
        }
        return routeCandidates;
    }

    private int groupTotal(Map<String, Object> intent) {
        Object groupValue = intent.get("group");
        if (groupValue instanceof Map<?, ?> group && group.get("total") instanceof Number total) {
            return Math.max(1, total.intValue());
        }
        return 2;
    }

    private List<Map<String, Object>> timelineFromStops(List<Poi> stops) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Poi poi = stops.get(i);
            Map<String, Object> timelineItem = new LinkedHashMap<>();
            timelineItem.put("time", tools.startTime(i));
            timelineItem.put("type", i == 2 ? "补充" : poi.getType() == PoiType.DINING ? "餐饮" : "活动");
            timelineItem.put("name", poi.getName());
            timelineItem.put("subtype", poi.getSubtype());
            timelineItem.put("address", poi.getAddress());
            timelineItem.put("durationMinutes", poi.getDurationMinutes());
            timelineItem.put("avgPrice", poi.getAvgPrice());
            timelineItem.put("rating", poi.getRating());
            timelineItem.put("lng", poi.getLng());
            timelineItem.put("lat", poi.getLat());
            timeline.add(timelineItem);
        }
        return timeline;
    }

    private List<Map<String, Object>> buildOptions(UUID planId, Map<String, Object> intent, List<Poi> candidates) {
        List<Poi> activities = tools.sortCandidates(candidates.stream()
                .filter(poi -> poi.getType() == PoiType.ENTERTAINMENT || poi.getType() == PoiType.CULTURE)
                .toList(), intent);
        List<Poi> dining = tools.sortCandidates(candidates.stream()
                .filter(poi -> poi.getType() == PoiType.DINING)
                .toList(), intent);
        List<Poi> extras = tools.sortCandidates(candidates.stream()
                .filter(poi -> poi.getType() == PoiType.EXTRA)
                .toList(), intent);

        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Poi activity = firstAvailableActivity(planId, activities, i);
            Poi restaurant = firstAvailableRestaurant(planId, dining, i);
            Poi extra = extras.get(Math.min(i, extras.size() - 1));
            List<Poi> stops = List.of(activity, restaurant, extra);
            Map<String, Object> route = routeEstimateTool.route(planId, stops);
            int totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
            if (totalMinutes < 240) {
                tools.recovery(planId, "conflict", "route shorter than 4 hours", "extend optional stop");
                extra = extendedPoi(extra, 240 - totalMinutes);
                stops = List.of(activity, restaurant, extra);
                totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
            }
            if (totalMinutes > 360) {
                Poi replacement = extras.get(0);
                tools.recovery(planId, "conflict", extra.getName(), replacement.getName());
                extra = replacement;
                stops = List.of(activity, restaurant, extra);
                route = routeEstimateTool.route(planId, stops);
                totalMinutes = tools.totalMinutes(stops, ((Number) route.get("travelMinutes")).intValue());
            }
            options.add(option(i + 1, stops, route, totalMinutes, intent));
        }
        List<Map<String, Object>> sorted = options.stream()
                .sorted(Comparator.comparing((Map<String, Object> it) -> ((Number) it.get("score")).doubleValue()).reversed())
                .toList();
        for (int index = 0; index < sorted.size(); index++) {
            sorted.get(index).put("rank", index + 1);
        }
        return sorted;
    }

    private Poi extendedPoi(Poi original, int extraMinutes) {
        return new Poi(
                original.getName(),
                original.getType(),
                original.getSubtype(),
                original.getAddress(),
                original.getLng(),
                original.getLat(),
                original.getDurationMinutes() + Math.max(0, extraMinutes),
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

    private Poi firstAvailableActivity(UUID planId, List<Poi> activities, int offset) {
        for (int i = offset; i < activities.size(); i++) {
            Poi activity = activities.get(i);
            if (tools.hasTicket(planId, activity)) return activity;
            Poi replacement = activities.stream().filter(p -> !p.isTicketProblem()).findFirst().orElse(activity);
            tools.recovery(planId, "no_ticket", activity.getName(), replacement.getName());
            return replacement;
        }
        return activities.get(0);
    }

    private Poi firstAvailableRestaurant(UUID planId, List<Poi> dining, int offset) {
        for (int i = offset; i < dining.size(); i++) {
            Poi restaurant = dining.get(i);
            if (tools.hasSeat(planId, restaurant)) return restaurant;
            Poi replacement = dining.stream().filter(p -> !p.isSeatProblem()).findFirst().orElse(restaurant);
            tools.recovery(planId, "no_seat", restaurant.getName(), replacement.getName());
            return replacement;
        }
        return dining.get(0);
    }

    private Map<String, Object> option(int rank, List<Poi> stops, Map<String, Object> route, int totalMinutes, Map<String, Object> intent) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Poi poi = stops.get(i);
            Map<String, Object> timelineItem = new LinkedHashMap<>();
            timelineItem.put("time", tools.startTime(i));
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
            timeline.add(timelineItem);
        }
        int budget = stops.stream().mapToInt(Poi::getAvgPrice).sum() * ((Number) ((Map<?, ?>) intent.get("group")).get("total")).intValue();
        double score = stops.stream().mapToDouble(Poi::getRating).average().orElse(4.0) * 20 + (360 - Math.abs(300 - totalMinutes)) / 10.0;
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("rank", rank);
        option.put("score", Math.round(score * 10.0) / 10.0);
        option.put("name", rank == 1 ? "稳妥轻松方案" : rank == 2 ? "体验丰富方案" : "备用省心方案");
        option.put("tagline", rank == 1 ? "距离更近，节奏更稳" : rank == 2 ? "内容更丰富，适合慢慢玩" : "作为临时调整也好执行");
        option.put("timeline", timeline);
        option.put("totalMinutes", totalMinutes);
        option.put("budgetEstimate", budget);
        option.put("route", route);
        option.put("fitReasons", List.of("覆盖活动、餐饮和补充行程", "总时长控制在4到6小时", "已检查座位和票务风险"));
        option.put("riskNotes", List.of("高峰期建议提前15分钟出发", "出发前建议再次确认营业状态"));
        option.put("executionList", List.of("活动购票", "餐厅订座或排队", "配送安排", "生成分享消息"));
        option.put("firstStop", stops.get(0).getName());
        option.put("diningName", stops.get(1).getName());
        option.put("lastStop", stops.get(2).getName());
        return option;
    }

    private PlanResponse toResponse(UUID id) {
        PlanSession session = planSessionRepository.findById(id).orElseThrow();
        Map<String, Object> intent = fromJson(session.getIntentJson());
        Map<String, Object> result = fromJson(session.getResultJson());
        List<Map<String, Object>> options = castList(result.getOrDefault("options", List.of()));
        List<Map<String, Object>> trace = toolCallLogRepository.findByPlanSessionIdOrderByCreatedAt(id).stream()
                .map(this::tracePayload)
                .toList();
        return new PlanResponse(id, session.getStatus().name(), intent, options, trace, fromJson(session.getExecutionJson()));
    }

    private Map<String, Object> findOption(UUID id, int rank) {
        return planOptionRepository.findByPlanSessionIdOrderByRankNo(id).stream()
                .filter(option -> option.getRankNo() == rank)
                .findFirst()
                .map(option -> fromJson(option.getOptionJson()))
                .orElseThrow();
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
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
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
}
