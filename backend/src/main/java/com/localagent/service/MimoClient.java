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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class MimoClient {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int FALLBACK_LANE_TIMEOUT_CAP_MS = 3200;
    private static final int RACE_LANE_TIMEOUT_CAP_MS = 2800;

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
        return completeWithMeta(systemPrompt, userPrompt).content();
    }

    public CompletionResult completeWithMeta(String systemPrompt, String userPrompt) {
        List<Endpoint> endpoints = endpoints();
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("MiMo API not configured");
        }
        if ("parallel-race".equalsIgnoreCase(properties.getLlm().getRouterMode()) && endpoints.size() > 1) {
            return completeRace(endpoints, systemPrompt, userPrompt);
        }
        List<String> failures = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        String fallbackReason = "";
        for (Endpoint endpoint : endpoints) {
            long start = System.currentTimeMillis();
            try {
                String content = completeOnce(endpoint, systemPrompt, userPrompt);
                return new CompletionResult(content, endpoint.lane(), endpoint.model(),
                        System.currentTimeMillis() - start, fallbackReason, failures);
            } catch (Exception e) {
                String reason = endpoint.lane() + ": " + safeReason(e);
                failures.add(reason);
                fallbackReason = reason;
            }
        }
        throw new IllegalStateException("MiMo API unavailable after " + (System.currentTimeMillis() - totalStart)
                + "ms; " + String.join("; ", failures));
    }

    private CompletionResult completeRace(List<Endpoint> endpoints, String systemPrompt, String userPrompt) {
        long totalStart = System.currentTimeMillis();
        List<CompletableFuture<CompletionResult>> futures = endpoints.stream()
                .map(endpoint -> CompletableFuture.supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        String content = completeOnce(endpoint, systemPrompt, userPrompt);
                        return new CompletionResult(content, endpoint.lane(), endpoint.model(),
                                System.currentTimeMillis() - start, "parallel-race", List.of());
                    } catch (Exception e) {
                        throw new CompletionException(new IllegalStateException(endpoint.lane() + ": " + safeReason(e), e));
                    }
                }))
                .toList();
        CompletableFuture<CompletionResult> winner = new CompletableFuture<>();
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (CompletableFuture<CompletionResult> future : futures) {
            future.whenComplete((result, error) -> {
                if (error == null) {
                    winner.complete(result);
                } else {
                    failures.add(safeReason(error instanceof Exception exception ? exception : new Exception(error)));
                    if (failures.size() == futures.size()) {
                        winner.completeExceptionally(new IllegalStateException("MiMo API unavailable after "
                                + (System.currentTimeMillis() - totalStart) + "ms; " + String.join("; ", failures)));
                    }
                }
            });
        }
        try {
            return winner.join();
        } catch (CompletionException e) {
            throw new IllegalStateException(safeReason(e), e);
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
    }

    private List<Endpoint> endpoints() {
        ExternalClientProperties.Llm llm = properties.getLlm();
        if (!llm.isEnabled()) {
            return List.of();
        }
        List<Endpoint> endpoints = new ArrayList<>();
        if (!isBlank(llm.getApiKey())) {
            endpoints.add(new Endpoint("primary", llm.getApiKey(), llm.getBaseUrl(), llm.getModel(),
                    llm.getMaxTokens(), cappedTimeout(llm.getTimeoutMs())));
        }
        ExternalClientProperties.Endpoint secondary = llm.getSecondary();
        if (secondary.isEnabled() && !isBlank(secondary.getApiKey())) {
            endpoints.add(new Endpoint("secondary",
                    secondary.getApiKey(),
                    isBlank(secondary.getBaseUrl()) ? llm.getBaseUrl() : secondary.getBaseUrl(),
                    isBlank(secondary.getModel()) ? llm.getModel() : secondary.getModel(),
                    secondary.getMaxTokens() > 0 ? secondary.getMaxTokens() : llm.getMaxTokens(),
                    cappedTimeout(secondary.getTimeoutMs() > 0 ? secondary.getTimeoutMs() : llm.getTimeoutMs())));
        }
        return endpoints;
    }

    private int cappedTimeout(int configuredTimeoutMs) {
        int cap = "parallel-race".equalsIgnoreCase(properties.getLlm().getRouterMode())
                ? RACE_LANE_TIMEOUT_CAP_MS
                : FALLBACK_LANE_TIMEOUT_CAP_MS;
        return Math.max(500, Math.min(configuredTimeoutMs, cap));
    }

    private String completeOnce(Endpoint endpoint, String systemPrompt, String userPrompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("max_completion_tokens", Math.max(256, endpoint.maxTokens()));
        body.put("temperature", properties.getLlm().getTemperature());
        body.put("top_p", 0.95);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        String sourceUrl = endpoint.baseUrl() + CHAT_COMPLETIONS_PATH;
        HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(Duration.ofMillis(endpoint.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("api-key", endpoint.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("MiMo API status error: " + response.statusCode());
            }
            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> choices = castList(payload.get("choices"));
            if (choices.isEmpty()) {
                throw new IllegalStateException("MiMo API returned no choices");
            }
            Map<String, Object> message = castMap(choices.get(0).get("message"));
            String content = String.valueOf(message.getOrDefault("content", "")).trim();
            if (content.isBlank()) {
                throw new IllegalStateException("MiMo API returned empty content");
            }
            return content;
        } catch (Exception e) {
            throw new IllegalStateException(safeReason(e), e);
        }
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

    private String safeReason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record Endpoint(String lane, String apiKey, String baseUrl, String model, int maxTokens, int timeoutMs) {}

    public record CompletionResult(String content, String lane, String model, long durationMs,
                                   String fallbackReason, List<String> failures) {}
}
