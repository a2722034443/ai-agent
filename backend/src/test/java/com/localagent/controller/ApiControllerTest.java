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

        String familyPlanMessage = "\u4eca\u5929\u4e0b\u5348\u5e26\u8001\u5a46\u5b69\u5b50\u51fa\u53bb\u73a9\uff0c"
                + "\u5b69\u5b505\u5c81\uff0c\u8001\u5a46\u5728\u51cf\u80a5\uff0c\u522b\u79bb\u5bb6\u592a\u8fdc";
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
