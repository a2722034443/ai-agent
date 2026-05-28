package com.localagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tool_call_log")
public class ToolCallLog {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID planSessionId;
    @Column(nullable = false)
    private String toolName;
    @Column(nullable = false)
    private String status;
    private long durationMs;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String inputJson;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String outputJson;
    private Instant createdAt;

    public ToolCallLog() {
    }

    public ToolCallLog(UUID planSessionId, String toolName, String status, long durationMs, String inputJson, String outputJson) {
        this.id = UUID.randomUUID();
        this.planSessionId = planSessionId;
        this.toolName = toolName;
        this.status = status;
        this.durationMs = durationMs;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPlanSessionId() { return planSessionId; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public long getDurationMs() { return durationMs; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public Instant getCreatedAt() { return createdAt; }
}
