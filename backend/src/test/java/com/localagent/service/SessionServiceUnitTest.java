package com.localagent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SessionServiceUnitTest {
    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);

    @Test
    void normalRuntimeStoresSessionInRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        SessionService sessionService = new SessionService(redisTemplate, 24, false);

        sessionService.create("auditor");

        verify(valueOperations).set(anyString(), eq("auditor"), any(Duration.class));
    }

    @Test
    void normalRuntimeDoesNotFallbackToMemoryWhenRedisFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        SessionService sessionService = new SessionService(redisTemplate, 24, false);

        assertThatThrownBy(() -> sessionService.create("auditor"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis down");
    }

    @Test
    void testRuntimeCanUseExplicitMockSessionStore() {
        SessionService sessionService = new SessionService(redisTemplate, 24, true);

        String token = sessionService.create("test").token();
        sessionService.validate(token);

        verify(redisTemplate, never()).opsForValue();
    }
}
