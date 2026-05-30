package com.localagent.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 令牌桶限速器：最多 3 并发，每 200ms 补充一个令牌（≈5 QPS，符合高德免费配额）。
 * 替换原来的全局串行锁，允许并行请求大幅降低总耗时。
 */
@Component
public class AmapRequestLimiter {
    private static final int MAX_CONCURRENT = 3;
    private static final long REFILL_INTERVAL_MS = 200L;
    private static final int MAX_TOKENS = 5;

    private final Semaphore concurrencySlots = new Semaphore(MAX_CONCURRENT, true);
    private final AtomicInteger tokens = new AtomicInteger(MAX_TOKENS);
    private final ScheduledExecutorService refiller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "amap-limiter-refill");
        t.setDaemon(true);
        return t;
    });

    public AmapRequestLimiter() {
        refiller.scheduleAtFixedRate(
            () -> tokens.updateAndGet(v -> Math.min(MAX_TOKENS, v + 1)),
            REFILL_INTERVAL_MS, REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
    }

    public void awaitSlot() {
        try {
            concurrencySlots.acquire();
            // 等待令牌可用
            while (tokens.decrementAndGet() < 0) {
                tokens.incrementAndGet();
                Thread.sleep(REFILL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void releaseSlot() {
        concurrencySlots.release();
    }

    public void backoff(int attempt) {
        sleep(300L + attempt * 200L);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        refiller.shutdownNow();
    }
}
