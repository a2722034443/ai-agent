package com.localagent.service;

import com.localagent.dto.ApiDtos.SessionResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final StringRedisTemplate redisTemplate;
    private final long ttlHours;
    private final boolean mockSessionStore;
    private final Map<String, Instant> localSessions = new ConcurrentHashMap<>();

    public SessionService(StringRedisTemplate redisTemplate,
                          @Value("${app.session-ttl-hours:24}") long ttlHours,
                          @Value("${app.mock-session-store:false}") boolean mockSessionStore) {
        this.redisTemplate = redisTemplate;
        this.ttlHours = ttlHours;
        this.mockSessionStore = mockSessionStore;
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
            } catch (RuntimeException e) {
                localSessions.put(token, expiresAt);
            }
        }
        return new SessionResponse(sessionId, token, expiresAt);
    }

    public void validate(String token) {
        if (token == null || token.isBlank()) {
            throw new SessionAuthException("会话已过期或不存在，请重新创建会话");
        }
        if (mockSessionStore) {
            validateLocal(token);
            return;
        }
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(token))) {
                return;
            }
        } catch (RuntimeException e) {
            validateLocal(token);
            return;
        }
        if (localSessions.containsKey(token)) {
            validateLocal(token);
            return;
        }
        throw new SessionAuthException("会话已过期或不存在，请重新创建会话");
    }

    private void validateLocal(String token) {
        Instant expiresAt = localSessions.get(token);
        if (expiresAt != null && expiresAt.isAfter(Instant.now())) {
            return;
        }
        throw new SessionAuthException("会话已过期或不存在，请重新创建会话");
    }
}
