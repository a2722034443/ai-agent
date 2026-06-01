package com.localagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_message")
public class ChatMessage {
    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID threadId;

    @Column(columnDefinition = "BINARY(16)")
    private UUID planSessionId;

    @Column(columnDefinition = "BINARY(16)")
    private UUID parentPlanSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, columnDefinition = "varchar(24)")
    private ChatMessageRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, columnDefinition = "varchar(40)")
    private ChatMessageKind kind;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String text;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false)
    private long sequenceNo;

    private Instant createdAt;

    public ChatMessage() {
    }

    public static ChatMessage create(UUID threadId, UUID planSessionId, UUID parentPlanSessionId,
                                     ChatMessageRole role, ChatMessageKind kind, String text,
                                     String payloadJson, long sequenceNo) {
        ChatMessage message = new ChatMessage();
        message.id = UUID.randomUUID();
        message.threadId = threadId;
        message.planSessionId = planSessionId;
        message.parentPlanSessionId = parentPlanSessionId;
        message.role = role;
        message.kind = kind;
        message.text = text;
        message.payloadJson = payloadJson;
        message.sequenceNo = sequenceNo;
        message.createdAt = Instant.now();
        return message;
    }

    public UUID getId() { return id; }
    public UUID getThreadId() { return threadId; }
    public UUID getPlanSessionId() { return planSessionId; }
    public UUID getParentPlanSessionId() { return parentPlanSessionId; }
    public ChatMessageRole getRole() { return role; }
    public ChatMessageKind getKind() { return kind; }
    public String getText() { return text; }
    public String getPayloadJson() { return payloadJson; }
    public long getSequenceNo() { return sequenceNo; }
    public Instant getCreatedAt() { return createdAt; }
}
