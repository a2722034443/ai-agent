package com.localagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plan_thread")
public class PlanThread {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String ownerClientId;

    private String ownerUserId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "BINARY(16)")
    private UUID latestPlanSessionId;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public PlanThread() {
    }

    public static PlanThread create(String ownerClientId, String title) {
        PlanThread thread = new PlanThread();
        thread.id = UUID.randomUUID();
        thread.ownerClientId = ownerClientId;
        thread.title = title;
        thread.createdAt = Instant.now();
        thread.updatedAt = thread.createdAt;
        return thread;
    }

    public UUID getId() { return id; }
    public String getOwnerClientId() { return ownerClientId; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getTitle() { return title; }
    public UUID getLatestPlanSessionId() { return latestPlanSessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }

    public void rename(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public void markLatestPlanSession(UUID latestPlanSessionId) {
        this.latestPlanSessionId = latestPlanSessionId;
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }
}
