package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class MimoClient {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final ExternalClientProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MimoClient(ExternalClientProperties properties, ObjectMapper objectMapper,
                      @Qualifier("llmHttpClient") HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public String complete(String systemPrompt, String userPrompt) {
        ExternalClientProperties.Llm llm = properties.getLlm();
        if (!llm.isEnabled() || isBlank(llm.getApiKey())) {
            throw new IllegalStateException("MiMo API 未配置");
        }
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return completeOnce(systemPrompt, userPrompt);
            } catch (Exception e) {
                lastError = e;
                if (attempt == 0 && isRetryable(e)) {
                    sleep(800L);
                    continue;
                }
                break;
            }
        }
        throw new IllegalStateException(lastError == null ? "MiMo API 调用失败" : lastError.getMessage(), lastError);
    }

    private String completeOnce(String systemPrompt, String userPrompt) throws Exception {
        ExternalClientProperties.Llm llm = properties.getLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.getModel());
        body.put("max_completion_tokens", Math.max(4096, llm.getMaxTokens()));
        body.put("temperature", llm.getTemperature());
        body.put("top_p", 0.95);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        String sourceUrl = llm.getBaseUrl() + CHAT_COMPLETIONS_PATH;
        HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(Duration.ofMillis(llm.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("api-key", llm.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("MiMo API 状态码异常：" + response.statusCode());
        }
        Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
        List<Map<String, Object>> choices = castList(payload.get("choices"));
        if (choices.isEmpty()) {
            throw new IllegalStateException("MiMo API 未返回 choices");
        }
        Map<String, Object> message = castMap(choices.get(0).get("message"));
        String content = String.valueOf(message.getOrDefault("content", "")).trim();
        if (content.isBlank()) {
            throw new IllegalStateException("MiMo API 返回内容为空");
        }
        return content;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isRetryable(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("返回内容为空")
                || message.contains("timed out")
                || message.contains("timeout")
                || message.contains("未返回 choices");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
