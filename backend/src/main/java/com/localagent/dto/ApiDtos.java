package com.localagent.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record SessionRequest(String nickname) {}
    public record SessionResponse(String sessionId, String token, Instant expiresAt) {}
    public record PlanRequest(String message) {}
    public record ConfirmRequest(int rank) {}
    public record FeedbackRequest(String message) {}

    public record PlanResponse(
            UUID planId,
            String status,
            Map<String, Object> intent,
            List<Map<String, Object>> options,
            List<Map<String, Object>> trace,
            Map<String, Object> execution
    ) {}
}
