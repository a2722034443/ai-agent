package com.localagent.service;

import org.springframework.stereotype.Component;

@Component
public class AmapRequestLimiter {
    private static final long MIN_INTERVAL_MS = 650L;
    private long nextAllowedAt = 0L;

    public synchronized void awaitSlot() {
        long now = System.currentTimeMillis();
        long waitMs = nextAllowedAt - now;
        if (waitMs > 0) {
            sleep(waitMs);
        }
        nextAllowedAt = System.currentTimeMillis() + MIN_INTERVAL_MS;
    }

    public void backoff(int attempt) {
        sleep(900L + attempt * 700L);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
