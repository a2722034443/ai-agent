package com.localagent.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @Test
    void rejectsMissingSessionToken() throws Exception {
        mockMvc.perform(post("/api/plans")
                        .header("X-Session-Token", "missing")
                        .contentType("application/json")
                        .content("{\"message\":\"afternoon plan\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void asksForClarificationBeforeCallingRealPlanningWhenRequiredFieldsAreMissing() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        mockMvc.perform(post("/api/plans")
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"想今天出去玩一下\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.clarification.fields").isArray());
    }

    @Test
    void nearbyFriendsRequestStillNeedsConcreteContextBeforePlanning() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        mockMvc.perform(post("/api/plans")
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"我想在我附近 和朋友玩 你看看附近都有啥 推荐一下\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='location')]").exists())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='timeWindow')]").exists())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='duration')]").exists())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='group')]").exists())
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='budget')]").exists());
    }

    @Test
    void broadAfternoonPeriodStillAsksForExactStartTime() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = extractJsonString(sessionResult, "token");

        String message = "今天下午在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右";
        mockMvc.perform(post("/api/plans")
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.options").isEmpty())
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
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.intent.time_window.start").value("11:00"))
                .andExpect(jsonPath("$.clarification.fields[?(@.key=='timeWindow')]").doesNotExist());
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
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"" + familyPlanMessage + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").exists())
                .andExpect(jsonPath("$.options[0].timeline").isArray())
                .andExpect(jsonPath("$.trace").isArray())
                .andExpect(jsonPath("$.trace[0].mode").exists())
                .andReturn();

        String planId = extractPlanId(planResult);

        mockMvc.perform(get("/api/plans/{id}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.options[0].rank").exists());

        mockMvc.perform(post("/api/plans/{id}/confirm", planId)
                        .contentType("application/json")
                        .content("{\"rank\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.execution.orders").isArray())
                .andExpect(jsonPath("$.execution.shareMessage").exists());

        String feedbackMessage = "\u4e0d\u8981\u592a\u8fdc\uff0c\u665a\u996d\u6362\u6e05\u6de1\u4e00\u70b9";
        mockMvc.perform(post("/api/plans/{id}/feedback", planId)
                        .contentType("application/json")
                        .content("{\"message\":\"" + feedbackMessage + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").exists())
                .andExpect(jsonPath("$.options[0].timeline").isArray());
    }

    private String extractPlanId(MvcResult result) throws Exception {
        return UUID.fromString(extractJsonString(result, "planId")).toString();
    }

    private String extractJsonString(MvcResult result, String field) throws Exception {
        String json = result.getResponse().getContentAsString();
        return objectMapper.readTree(json).get(field).asText();
    }
}
