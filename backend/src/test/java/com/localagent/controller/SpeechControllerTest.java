package com.localagent.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.service.MimoClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "external.asr.provider=mock",
        "external.asr.max-audio-mb=1"
})
class SpeechControllerTest {
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
    void transcribesUploadedAudioWithSessionToken() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"voice\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(sessionResult.getResponse().getContentAsString()).get("token").asText();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "voice.wav",
                "audio/wav",
                new byte[] {82, 73, 70, 70, 1, 2, 3, 4}
        );

        mockMvc.perform(multipart("/api/speech/transcribe")
                        .file(file)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").isNotEmpty())
                .andExpect(jsonPath("$.engine").value("mock"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void rejectsMissingAudioFile() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        MvcResult sessionResult = mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"nickname\":\"voice\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(sessionResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(multipart("/api/speech/transcribe")
                        .header("X-Session-Token", token))
                .andExpect(status().isBadRequest());
    }
}
