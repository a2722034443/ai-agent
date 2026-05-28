package com.localagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mock_order")
public class MockOrder {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID planSessionId;
    @Column(nullable = false)
    private String orderNo;
    @Column(nullable = false)
    private String actionType;
    @Column(nullable = false)
    private String targetName;
    @Column(nullable = false)
    private String status;
    private Instant createdAt;

    public MockOrder() {
    }

    public MockOrder(UUID planSessionId, String orderNo, String actionType, String targetName, String status) {
        this.id = UUID.randomUUID();
        this.planSessionId = planSessionId;
        this.orderNo = orderNo;
        this.actionType = actionType;
        this.targetName = targetName;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPlanSessionId() { return planSessionId; }
    public String getOrderNo() { return orderNo; }
    public String getActionType() { return actionType; }
    public String getTargetName() { return targetName; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
