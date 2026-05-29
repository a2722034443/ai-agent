package com.localagent.service;

import com.localagent.dto.ApiDtos.SessionResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final StringRedisTemplate redisTemplate;
    private final long ttlHours;
    private final boolean mockSessionStore;
    private final boolean localFallback;
    private final Map<String, Instant> localSessions = new ConcurrentHashMap<>();

    public SessionService(StringRedisTemplate redisTemplate,
                          @Value("${app.session-ttl-hours:24}") long ttlHours,
                          @Value("${app.mock-session-store:false}") boolean mockSessionStore,
                          @Value("${app.session-local-fallback:true}") boolean localFallback) {
        this.redisTemplate = redisTemplate;
        this.ttlHours = ttlHours;
        this.mockSessionStore = mockSessionStore;
        this.localFallback = localFallback;
    }

    public SessionResponse create(String nickname) {
        String sessionId = UUID.randomUUID().toString();
        String token = "sess_" + UUID.randomUUID();
        String value = (nickname == null || nickname.isBlank()) ? "guest" : nickname.trim();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(ttlHours));
        if (mockSessionStore) {
            localSessions.put(token, expiresAt);
        } else {
            try {
                redisTemplate.opsForValue().set(token, value, Duration.ofHours(ttlHours));
            } catch (RedisConnectionFailureException e) {
                if (!localFallback) {
                    throw e;
                }
                localSessions.put(token, expiresAt);
                log.warn("Redis 不可用，session 临时保存到本进程内存；重启后该 token 会失效。", e);
            }
        }
        return new SessionResponse(sessionId, token, expiresAt);
    }

    public void validate(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Invalid or expired session token");
        }
        if (mockSessionStore) {
            validateLocal(token);
            return;
        }
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(token))) {
                return;
            }
        } catch (RedisConnectionFailureException e) {
            if (!localFallback) {
                throw e;
            }
            validateLocal(token);
            return;
        }
        throw new IllegalArgumentException("Invalid or expired session token");
    }

    private void validateLocal(String token) {
        Instant expiresAt = localSessions.get(token);
        if (expiresAt != null && expiresAt.isAfter(Instant.now())) {
            return;
        }
        throw new IllegalArgumentException("Invalid or expired session token");
    }
}
