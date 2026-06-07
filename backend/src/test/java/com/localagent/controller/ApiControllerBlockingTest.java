package com.localagent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.service.MimoClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:blocking_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.mock-session-store=true",
        "app.allow-mock-poi=false",
        "external.amap.enabled=true",
        "external.amap.web-service-key=",
        "external.search.enabled=false",
        "external.llm.enabled=false"
})
class ApiControllerBlockingTest {
    @MockBean
    private StringRedisTemplate redisTemplate;
    @MockBean
    private MimoClient mimoClient;

    @jakarta.annotation.Resource
    private MockMvc mockMvc;
    @jakarta.annotation.Resource
    private ObjectMapper objectMapper;

    @Test
    void normalProfileBlocksWhenAmapKeyIsMissing() throws Exception {
        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"测试\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(sessionResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/plans")
                        .header("X-Client-Id", "client-test")
                        .header("X-Session-Token", token)
                        .contentType("application/json")
                        .content("{\"message\":\"今天下午2点在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.provider").value("amap"))
                .andExpect(jsonPath("$.error").value("抱歉，暂时无法获取地点信息，请稍后重试"))
                .andExpect(jsonPath("$.trace").isArray())
                .andExpect(jsonPath("$.trace[?(@.mode == 'blocked')]").exists());
    }
}
