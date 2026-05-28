package com.localagent.service;

import java.util.UUID;

public class PlanBlockedException extends RuntimeException {
    private final UUID planId;
    private final String provider;
    private final int httpStatus;

    public PlanBlockedException(UUID planId, String provider, String message, int httpStatus) {
        super(message);
        this.planId = planId;
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getProvider() {
        return provider;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
