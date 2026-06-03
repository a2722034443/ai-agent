package com.localagent.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.service.MimoClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "external.amap.default-origin=121.588000,38.883000")
class ApiControllerTest {
    private static final String CLIENT_ID = "client-test";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @MockBean
    private MimoClient mimoClient;

    @Test
    void rejectsMissingSessionToken() throws Exception {
        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", "missing")
                        .contentType("application/json")
                        .content("{\"message\":\"afternoon plan\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createsSessionWithEmptyBody() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void asksForClarificationBeforeCallingRealPlanningWhenRequiredFieldsAreMissing() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("test fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"想今天出去玩一下\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.clarification.fields").isArray())
                .andExpect(jsonPath("$.clarification.fields[0].type").value("text"))
                .andExpect(jsonPath("$.clarification.fields[0].allowCustom").value(true))
                .andExpect(jsonPath("$.clarification.fields[0].options").isArray())
                .andExpect(jsonPath("$.warnings[0]").value("信息补齐前不会查询真实地点，也不会生成方案。"))
                .andExpect(jsonPath("$.trace[?(@.tool=='AmapPoiSearchTool')]").doesNotExist())
                .andExpect(jsonPath("$.trace[?(@.tool=='AmapWeatherTool')]").doesNotExist())
                .andExpect(jsonPath("$.trace[?(@.tool=='WebSearchTool')]").doesNotExist())
                .andExpect(jsonPath("$.trace[?(@.tool=='AmapRouteEstimateTool')]").doesNotExist());
    }

    @Test
    void durationClarificationExplainsStartTimeWasRecognized() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("fast fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String message = "今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。";
        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", message,
                                "planCount", 3,
                                "stopCountPreference", "标准"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.intent.time_window.start").value("19:00"))
                .andExpect(jsonPath("$.clarification.fields[0].key").value("duration"))
                .andExpect(jsonPath("$.clarification.fields[0].question").value("已识别开始时间 19:00，还需要知道大概玩多久，或者最晚几点结束。"));
    }

    @Test
    void durationClarificationAnswerUsesPreviousPlanContextAndReturnsOptions() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("fast fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String message = "今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。";
        MvcResult firstResult = mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", message,
                                "planCount", 3,
                                "stopCountPreference", "标准"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.clarification.fields[0].key").value("duration"))
                .andReturn();

        String previousPlanId = extractPlanId(firstResult);
        String secondBody = objectMapper.writeValueAsString(Map.of(
                "message", "3小时左右",
                "planCount", 3,
                "stopCountPreference", "标准",
                "previousPlanId", previousPlanId,
                "clarificationAnswers", Map.of("duration", "3小时左右")
        ));

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(secondBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.intent.location.city").value("上海"))
                .andExpect(jsonPath("$.intent.time_window.start").value("19:00"))
                .andExpect(jsonPath("$.intent.time_window.durationMinutes").value(180))
                .andExpect(jsonPath("$.intent.group.total").value(4))
                .andExpect(jsonPath("$.intent.soft_preferences.budgetAmount").value(800))
                .andExpect(jsonPath("$.options[0].timeline").isArray())
                .andExpect(jsonPath("$.options[0].timeline.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.options[0].route.distanceKm").exists())
                .andExpect(jsonPath("$.trace[?(@.tool=='AmapPoiSearchTool')]").exists())
                .andExpect(jsonPath("$.trace[?(@.tool=='AmapRouteEstimateTool')]").exists());
    }

    @Test
    void nearbyFriendsRequestUsesDefaultOriginButStillNeedsOtherRequiredContext() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("test fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"我想在我附近 和朋友玩 你看看附近都有啥 推荐一下\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.intent.location.lng").value(121.588))
                .andExpect(jsonPath("$.intent.location.lat").value(38.883))
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='location')]").doesNotExist())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='timeWindow')]").exists())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='duration')]").exists())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='group')]").exists())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='budget')]").exists());
    }

    @Test
    void broadAfternoonPeriodStillAsksForExactStartTime() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("test fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String message = "今天下午在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右";
        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.intent.time_window.start").doesNotExist())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='timeWindow')]").exists());
    }

    @Test
    void clarificationAnswersAcceptDottedTimeAndContinuePlanning() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String body = """
                {
                  "message": "下午想安排文化展览和清淡晚餐，步行距离尽量短，不要太吵",
                  "planCount": 5,
                  "stopCountPreference": "丰富",
                  "clarificationAnswers": {
                    "location": "大连金石滩大连民族大学附近",
                    "timeWindow": "11.00",
                    "group": "3个男人",
                    "duration": "4小时左右",
                    "budget": "300",
                    "preferences": "文化展览和清淡晚餐"
                  }
                }
                """;

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.intent.time_window.start").value("11:00"))
                .andExpect(jsonPath("$.intent.userFacts.answers.timeWindow").value("11.00"))
                .andExpect(jsonPath("$.intent.userFacts.answers.budget").value("300"))
                .andExpect(jsonPath("$.intent.derived.location").exists())
                .andExpect(jsonPath("$.intent.poiSearchStrategy.activityKeywords").isArray())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='timeWindow')]").doesNotExist());
    }

    @Test
    void vagueNearbyAnswerUsesDefaultOriginAndStillAsksForInvalidTime() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenReturn("""
                {
                  "message": "我还需要确认两个关键信息，补齐后再查真实地点。",
                  "missingFields": ["location", "timeWindow"],
                  "fields": [
                    {
                      "key": "location",
                      "label": "地点",
                      "question": "你现在在哪个城市的哪个商圈、地标或地址附近？",
                      "type": "text",
                      "suggestions": [],
                      "allowCustom": true,
                      "reason": "我附近无法定位"
                    },
                    {
                      "key": "timeWindow",
                      "label": "开始时间",
                      "question": "早上12点表达不清晰，请选择明确开始时间。",
                      "type": "choice",
                      "suggestions": ["09:30", "10:00", "10:30"],
                      "allowCustom": true,
                      "reason": "时间表达冲突"
                    }
                  ]
                }
                """);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String body = """
                {
                  "message": "在我附近，想出去玩玩",
                  "planCount": 3,
                  "stopCountPreference": "标准",
                  "clarificationAnswers": {
                    "location": "我附近",
                    "timeWindow": "早上12点",
                    "duration": "3小时左右",
                    "group": "情侣两人",
                    "budget": "600",
                    "preferences": "亲子活动和晚餐"
                  }
                }
                """;

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.intent.location.lng").value(121.588))
                .andExpect(jsonPath("$.intent.location.lat").value(38.883))
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='location')]").doesNotExist())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='timeWindow')]").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("大连"))))
                .andExpect(jsonPath("$.trace[?(@.tool=='ClarificationAgent' && @.mode=='rule')]").exists())
                .andExpect(jsonPath("$.clarification.fields[0].allowCustom").value(true))
                .andExpect(jsonPath("$.trace[?(@.tool=='ClarificationAgent')]").exists());
    }

    @Test
    void placeholderLocationAnswerStillNeedsClarification() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("test fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String body = """
                {
                  "message": "想在附近玩玩",
                  "planCount": 3,
                  "stopCountPreference": "标准",
                  "clarificationAnswers": {
                    "location": "我所在城市 + 地铁站/地标",
                    "timeWindow": "10:00",
                    "duration": "4小时左右",
                    "group": "我自己",
                    "budget": "300",
                    "preferences": "轻松逛逛和吃饭"
                  }
                }
                """;

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='location')]").exists());
    }

    @Test
    void browserCoordinateLocationCanContinuePlanning() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String body = """
                {
                  "message": "想在附近玩玩",
                  "planCount": 3,
                  "stopCountPreference": "标准",
                  "clarificationAnswers": {
                    "location": "当前位置 121.588000,38.883000",
                    "timeWindow": "10:00",
                    "duration": "4小时左右",
                    "group": "我自己",
                    "budget": "300",
                    "preferences": "轻松逛逛和吃饭"
                  }
                }
                """;

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.options").isArray())
                .andExpect(jsonPath("$.intent.userFacts.answers.location").value("当前位置 121.588000,38.883000"))
                .andExpect(jsonPath("$.intent.location.lng").value(121.588))
                .andExpect(jsonPath("$.intent.location.lat").value(38.883));
    }

    @Test
    void currentLocationAnswerUsesConfiguredDefaultOriginAndNoCompanionMeansSolo() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("fast fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String body = """
                {
                  "message": "我想在我附近游玩，想去吃个烤肉，看个电影，再去海边玩玩。大约3小时。早上10点出发",
                  "planCount": 3,
                  "stopCountPreference": "标准",
                  "clarificationAnswers": {
                    "location": "当前位置",
                    "group": "无",
                    "budget": "500"
                  }
                }
                """;

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.options").isArray())
                .andExpect(jsonPath("$.options[0].timeline.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.intent.location.lng").value(121.588))
                .andExpect(jsonPath("$.intent.location.lat").value(38.883))
                .andExpect(jsonPath("$.intent.group.total").value(1))
                .andExpect(jsonPath("$.intent.group.composition").value("单人"))
                .andExpect(jsonPath("$.intent.poiSearchStrategy.activityKeywords").value(org.hamcrest.Matchers.hasItem("电影院")))
                .andExpect(jsonPath("$.intent.poiSearchStrategy.diningKeywords").value(org.hamcrest.Matchers.hasItem("烤肉")));
    }

    @Test
    void feedbackOnClarificationPlanDoesNotFailWithMissingOption() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("test fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        MvcResult planResult = mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"想在附近玩玩\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andReturn();
        String planId = extractPlanId(planResult);

        mockMvc.perform(post("/api/plans/{id}/feedback", planId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"预算太高了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.clarification.fields").isArray());

        mockMvc.perform(post("/api/plans/{id}/confirm", planId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"rank\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void missingPlanReturnsNotFoundInsteadOfServerError() throws Exception {
        mockMvc.perform(get("/api/plans/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void runsFullPlanExecutionAndFeedbackApiChain() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String familyPlanMessage = "今天下午2点在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右";
        MvcResult planResult = mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"" + familyPlanMessage + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadId").exists())
                .andExpect(jsonPath("$.planId").exists())
                .andExpect(jsonPath("$.options[0].timeline").isArray())
                .andExpect(jsonPath("$.trace").isArray())
                .andExpect(jsonPath("$.trace[0].mode").exists())
                .andReturn();

        String threadId = extractJsonString(planResult, "threadId");
        String planId = extractPlanId(planResult);

        mockMvc.perform(get("/api/plans/{id}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.options[0].rank").exists());

        mockMvc.perform(post("/api/plans/{id}/confirm", planId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"rank\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.execution.orders").isArray())
                .andExpect(jsonPath("$.execution.shareMessage").exists());

        mockMvc.perform(get("/api/history/threads/{id}", threadId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.messages[-1:].kind").value(org.hamcrest.Matchers.hasItem("ASSISTANT_PLAN_RESULT")))
                .andExpect(jsonPath("$.messages[-1:].payload.currentView").value(org.hamcrest.Matchers.hasItem("collab")))
                .andExpect(jsonPath("$.messages[-1:].payload.selectedRank").value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.messages[-1:].payload.executionSteps").isArray());

        mockMvc.perform(post("/api/plans/{id}/confirm", planId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"rank\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error").exists());

        String feedbackMessage = "\u4e0d\u8981\u592a\u8fdc\uff0c\u665a\u996d\u6362\u6e05\u6de1\u4e00\u70b9";
        mockMvc.perform(post("/api/plans/{id}/feedback", planId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"" + feedbackMessage + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").exists())
                .andExpect(jsonPath("$.options[0].timeline").isArray());
    }

    @Test
    void storesMessageLevelHistoryAndSupportsRenameDelete() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.complete(anyString(), anyString())).thenThrow(new IllegalStateException("fast fallback"));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String firstMessage = "今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。";
        MvcResult firstPlan = mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", firstMessage,
                                "planCount", 3,
                                "stopCountPreference", "标准"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadId").exists())
                .andExpect(jsonPath("$.assistantMessageId").exists())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andReturn();

        String threadId = extractJsonString(firstPlan, "threadId");
        String previousPlanId = extractPlanId(firstPlan);

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "3小时左右",
                                "threadId", threadId,
                                "previousPlanId", previousPlanId,
                                "planCount", 3,
                                "stopCountPreference", "标准",
                                "clarificationAnswers", Map.of("duration", "3小时左右")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadId").value(threadId))
                .andExpect(jsonPath("$.status").value("READY"));

        mockMvc.perform(get("/api/history/threads")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].threadId").value(threadId))
                .andExpect(jsonPath("$[0].lastStatus").value("READY"));

        mockMvc.perform(get("/api/history/threads/{id}", threadId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.messages[0].kind").value("USER_TEXT"))
                .andExpect(jsonPath("$.messages[1].kind").value("ASSISTANT_CLARIFICATION"))
                .andExpect(jsonPath("$.messages[1].payload.currentStep").value("clarify"))
                .andExpect(jsonPath("$.messages[3].kind").value("ASSISTANT_PLAN_RESULT"))
                .andExpect(jsonPath("$.messages[3].payload.currentStep").value("plans"));

        mockMvc.perform(patch("/api/history/threads/{id}", threadId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"title\":\"新的线程标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新的线程标题"));

        mockMvc.perform(delete("/api/history/threads/{id}", threadId)
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/history/threads")
                        .header("X-Client-Id", CLIENT_ID)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.threadId=='" + threadId + "')]").isEmpty());
    }

    private String extractPlanId(MvcResult result) throws Exception {
        return UUID.fromString(extractJsonString(result, "planId")).toString();
    }

    private String extractJsonString(MvcResult result, String field) throws Exception {
        String json = result.getResponse().getContentAsString();
        return objectMapper.readTree(json).get(field).asText();
    }
}
