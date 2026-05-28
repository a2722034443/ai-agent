package com.localagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feedback_event")
public class FeedbackEvent {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID planSessionId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;
    private Instant createdAt;

    public FeedbackEvent() {
    }

    public FeedbackEvent(UUID planSessionId, String message) {
        this.id = UUID.randomUUID();
        this.planSessionId = planSessionId;
        this.message = message;
        this.createdAt = Instant.now();
    }
}
