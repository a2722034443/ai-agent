package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.dto.ApiDtos.ChatMessageResponse;
import com.localagent.dto.ApiDtos.ThreadDetailResponse;
import com.localagent.dto.ApiDtos.ThreadSummaryResponse;
import com.localagent.model.ChatMessage;
import com.localagent.model.ChatMessageKind;
import com.localagent.model.ChatMessageRole;
import com.localagent.model.PlanThread;
import com.localagent.repo.ChatMessageRepository;
import com.localagent.repo.PlanThreadRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoryService {
    private static final int TITLE_LIMIT = 24;

    private final PlanThreadRepository planThreadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public HistoryService(PlanThreadRepository planThreadRepository,
                          ChatMessageRepository chatMessageRepository,
                          ObjectMapper objectMapper) {
        this.planThreadRepository = planThreadRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlanThread createThread(String clientId, String firstUserMessage) {
        PlanThread thread = PlanThread.create(clientId, defaultTitle(firstUserMessage));
        return planThreadRepository.save(thread);
    }

    @Transactional(readOnly = true)
    public PlanThread requireThread(UUID threadId, String clientId) {
        return planThreadRepository.findByIdAndOwnerClientIdAndDeletedAtIsNull(threadId, clientId)
                .orElseThrow(NoSuchElementException::new);
    }

    @Transactional
    public void markLatestPlanSession(PlanThread thread, UUID planSessionId) {
        thread.markLatestPlanSession(planSessionId);
        planThreadRepository.save(thread);
    }

    @Transactional
    public ChatMessage appendUserText(UUID threadId, UUID parentPlanSessionId, String text) {
        return append(threadId, null, parentPlanSessionId, ChatMessageRole.user, ChatMessageKind.USER_TEXT, text, Map.of());
    }

    @Transactional
    public ChatMessage appendAssistant(UUID threadId, UUID planSessionId, UUID parentPlanSessionId,
                                       ChatMessageKind kind, String text, Object payload) {
        return append(threadId, planSessionId, parentPlanSessionId, ChatMessageRole.assistant, kind, text, payload);
    }

    @Transactional(readOnly = true)
    public List<ThreadSummaryResponse> listThreads(String clientId) {
        return planThreadRepository.findByOwnerClientIdAndDeletedAtIsNullOrderByUpdatedAtDesc(clientId).stream()
                .map(thread -> {
                    ChatMessage lastMessage = chatMessageRepository.findTopByThreadIdOrderBySequenceNoDesc(thread.getId()).orElse(null);
                    return new ThreadSummaryResponse(
                            thread.getId(),
                            thread.getTitle(),
                            lastMessage == null ? "" : preview(lastMessage.getText()),
                            lastMessage == null ? thread.getUpdatedAt() : lastMessage.getCreatedAt(),
                            lastMessage == null ? "EMPTY" : statusOf(lastMessage)
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ThreadDetailResponse getThread(String clientId, UUID threadId) {
        PlanThread thread = requireThread(threadId, clientId);
        List<ChatMessageResponse> messages = chatMessageRepository.findByThreadIdOrderBySequenceNoAsc(threadId).stream()
                .map(this::toResponse)
                .toList();
        return new ThreadDetailResponse(thread.getId(), thread.getTitle(), thread.getCreatedAt(), thread.getUpdatedAt(), messages);
    }

    @Transactional
    public ThreadDetailResponse renameThread(String clientId, UUID threadId, String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        PlanThread thread = requireThread(threadId, clientId);
        thread.rename(normalized);
        planThreadRepository.save(thread);
        return getThread(clientId, threadId);
    }

    @Transactional
    public void deleteThread(String clientId, UUID threadId) {
        PlanThread thread = requireThread(threadId, clientId);
        thread.softDelete();
        planThreadRepository.save(thread);
    }

    private ChatMessage append(UUID threadId, UUID planSessionId, UUID parentPlanSessionId,
                               ChatMessageRole role, ChatMessageKind kind, String text, Object payload) {
        long nextSequence = chatMessageRepository.findTopByThreadIdOrderBySequenceNoDesc(threadId)
                .map(item -> item.getSequenceNo() + 1)
                .orElse(1L);
        String payloadJson = toJson(payload == null ? Map.of() : payload);
        ChatMessage message = ChatMessage.create(threadId, planSessionId, parentPlanSessionId, role, kind,
                text == null ? "" : text, payloadJson, nextSequence);
        ChatMessage saved = chatMessageRepository.save(message);
        planThreadRepository.findById(threadId).ifPresent(thread -> {
            thread.touch();
            planThreadRepository.save(thread);
        });
        return saved;
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getThreadId(),
                message.getPlanSessionId(),
                message.getRole().name(),
                message.getKind().name(),
                message.getText(),
                fromJson(message.getPayloadJson()),
                message.getSequenceNo(),
                message.getCreatedAt()
        );
    }

    private String statusOf(ChatMessage message) {
        return switch (message.getKind()) {
            case ASSISTANT_CLARIFICATION -> "NEEDS_CLARIFICATION";
            case ASSISTANT_ERROR -> "ERROR";
            case ASSISTANT_PLAN_RESULT -> {
                Map<String, Object> payload = fromJson(message.getPayloadJson());
                if (!fromMap(payload.get("execution")).isEmpty()) {
                    yield "COMPLETED";
                }
                if (!castList(payload.get("options")).isEmpty()) {
                    yield "READY";
                }
                yield stringValue(payload.get("status"), "READY");
            }
            default -> "ACTIVE";
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String preview(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() <= 48 ? value : value.substring(0, 48) + "...";
    }

    private String defaultTitle(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) {
            return "新对话";
        }
        return value.length() <= TITLE_LIMIT ? value : value.substring(0, TITLE_LIMIT) + "...";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
