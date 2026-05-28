package com.localagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "plan_option")
public class PlanOption {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID planSessionId;
    @Column(nullable = false)
    private int rankNo;
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String optionJson;

    public PlanOption() {
    }

    public PlanOption(UUID planSessionId, int rankNo, String optionJson) {
        this.id = UUID.randomUUID();
        this.planSessionId = planSessionId;
        this.rankNo = rankNo;
        this.optionJson = optionJson;
    }

    public UUID getId() { return id; }
    public UUID getPlanSessionId() { return planSessionId; }
    public int getRankNo() { return rankNo; }
    public String getOptionJson() { return optionJson; }
}
