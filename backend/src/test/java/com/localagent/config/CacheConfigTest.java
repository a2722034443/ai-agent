package com.localagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CacheConfigTest {
    @Autowired
    private CacheManager cacheManager;

    @Test
    void exposesNamedPlanningCachesWithoutRequiringExternalRedisInTests() {
        assertThat(cacheManager.getCache("poi")).isNotNull();
        assertThat(cacheManager.getCache("geocode")).isNotNull();
        assertThat(cacheManager.getCache("route")).isNotNull();
        assertThat(cacheManager.getCache("poi-detail")).isNotNull();
    }
}
