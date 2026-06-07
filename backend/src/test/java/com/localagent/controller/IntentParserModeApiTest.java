package com.localagent.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localagent.service.MimoClient;
import java.util.List;
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
@TestPropertySource(properties = {
        "external.llm.enabled=true",
        "external.llm.intent-parser-mode=llm-fallback",
        "external.amap.default-origin=121.588000,38.883000"
})
class IntentParserModeApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @MockBean
    private MimoClient mimoClient;

    @Test
    void llmFallbackModeUsesMimoForInitialIntentParsing() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(mimoClient.completeWithMeta(anyString(), anyString())).thenReturn(new MimoClient.CompletionResult("""
                {
                  "scenario": "friends",
                  "location": {"city": "上海", "district": "静安寺", "radius": "nearby"},
                  "time_window": {"start": "19:00", "durationMinutes": 180},
                  "group": {"total": 4, "composition": "朋友同行", "hasChildren": false, "hasElderly": false},
                  "hard_constraints": ["低步行"],
                  "soft_preferences": {"budget": "medium", "budgetAmount": 800, "vibe": "朋友小聚"},
                  "poiSearchStrategy": {
                    "activityKeywords": ["展览"],
                    "diningKeywords": ["聚餐"],
                    "extraKeywords": ["咖啡"]
                  },
                  "confidence": 0.91
                }
                """, "primary", "mimo-test", 18, "", List.of()));

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = sessionResult.getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", "client-test")
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"今晚朋友小聚，帮我安排一下\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.intent.confidence").value(0.91))
                .andExpect(jsonPath("$.trace[?(@.tool=='IntentParserAgent' && @.provider=='mimo' && @.mode=='real')]").exists());
    }

    @Test
    void llmFallbackModeSkipsMimoWhenRuleParseAlreadyNeedsClarification() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"auditor\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = sessionResult.getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", "client-test")
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"在附近吃点小吃，帮我订座，吃完叫车回家，预算200\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.clarification.missingFields").isArray())
                .andExpect(jsonPath("$.clarification.missingFields[?(@=='location')]").exists())
                .andExpect(jsonPath("$.intent.location.lng").doesNotExist())
                .andExpect(jsonPath("$.intent.location.lat").doesNotExist());

        verify(mimoClient, never()).completeWithMeta(anyString(), anyString());
    }
}
