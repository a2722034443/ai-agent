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
@Table(name = "plan_session")
public class PlanSession {
    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID threadId;

    @Column(columnDefinition = "BINARY(16)")
    private UUID parentPlanSessionId;

    @Column(nullable = false)
    private String sessionToken;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String rawInput;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    private PlanTurnType turnType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    private PlanStatus status;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String intentJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String executionJson;

    private Instant createdAt;
    private Instant updatedAt;

    public PlanSession() {
    }

    public static PlanSession create(UUID threadId, UUID parentPlanSessionId, PlanTurnType turnType,
                                     String token, String rawInput) {
        PlanSession session = new PlanSession();
        session.id = UUID.randomUUID();
        session.threadId = threadId;
        session.parentPlanSessionId = parentPlanSessionId;
        session.sessionToken = token;
        session.rawInput = rawInput;
        session.turnType = turnType;
        session.status = PlanStatus.PLANNING;
        session.createdAt = Instant.now();
        session.updatedAt = session.createdAt;
        return session;
    }

    public UUID getId() { return id; }
    public UUID getThreadId() { return threadId; }
    public UUID getParentPlanSessionId() { return parentPlanSessionId; }
    public String getSessionToken() { return sessionToken; }
    public String getRawInput() { return rawInput; }
    public PlanTurnType getTurnType() { return turnType; }
    public PlanStatus getStatus() { return status; }
    public String getIntentJson() { return intentJson; }
    public String getResultJson() { return resultJson; }
    public String getExecutionJson() { return executionJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void markReady(String intentJson, String resultJson) {
        this.intentJson = intentJson;
        this.resultJson = resultJson;
        this.status = PlanStatus.READY;
        this.updatedAt = Instant.now();
    }

    public void markNeedsClarification(String intentJson, String resultJson) {
        this.intentJson = intentJson;
        this.resultJson = resultJson;
        this.status = PlanStatus.NEEDS_CLARIFICATION;
        this.updatedAt = Instant.now();
    }

    public void markBlocked(String intentJson, String resultJson) {
        this.intentJson = intentJson;
        this.resultJson = resultJson;
        this.status = PlanStatus.BLOCKED;
        this.updatedAt = Instant.now();
    }

    public void markExecuting() {
        this.status = PlanStatus.EXECUTING;
        this.updatedAt = Instant.now();
    }

    public void markCompleted(String executionJson) {
        this.executionJson = executionJson;
        this.status = PlanStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }
}
