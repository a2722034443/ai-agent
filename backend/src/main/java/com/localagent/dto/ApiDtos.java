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
    public record PlanRequest(
            String message,
            Integer planCount,
            String stopCountPreference,
            Map<String, Object> clarificationAnswers,
            UUID previousPlanId,
            UUID threadId
    ) {}
    public record NearbyPoiRequest(
            Double lng,
            Double lat,
            Integer radius,
            List<NearbyPoiCategoryRequest> categories
    ) {}
    public record NearbyPoiCategoryRequest(
            String key,
            String label,
            String keyword,
            Integer limit
    ) {}
    public record ConfirmRequest(int rank) {}
    public record FeedbackRequest(String message) {}
    public record ShareRequest(UUID planId, Integer selectedRank) {}
    public record VoteRequest(Integer rank, String voter) {}
    public record CommentRequest(String author, String text) {}
    public record RenameThreadRequest(String title) {}

    public record PlanResponse(
            UUID planId,
            UUID threadId,
            String status,
            Map<String, Object> intent,
            List<Map<String, Object>> options,
            List<Map<String, Object>> trace,
            Map<String, Object> execution,
            Map<String, Object> clarification,
            Map<String, Object> weather,
            List<String> warnings,
            UUID assistantMessageId
    ) {}

    public record ThreadSummaryResponse(
            UUID threadId,
            String title,
            String lastMessagePreview,
            Instant lastMessageAt,
            String lastStatus
    ) {}

    public record ChatMessageResponse(
            UUID id,
            UUID threadId,
            UUID planSessionId,
            String role,
            String kind,
            String text,
            Map<String, Object> payload,
            long sequenceNo,
            Instant createdAt
    ) {}

    public record ThreadDetailResponse(
            UUID threadId,
            String title,
            Instant createdAt,
            Instant updatedAt,
            List<ChatMessageResponse> messages
    ) {}
}
