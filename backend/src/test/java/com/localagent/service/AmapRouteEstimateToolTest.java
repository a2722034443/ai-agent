package com.localagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import com.localagent.model.Poi;
import com.localagent.model.PoiType;
import com.localagent.repo.MockOrderRepository;
import com.localagent.repo.PoiRepository;
import com.localagent.repo.ToolCallLogRepository;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.Authenticator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class AmapRouteEstimateToolTest {
    @Test
    void routeFallsBackQuicklyWhenSegmentRequestsHang() {
        ExternalClientProperties properties = new ExternalClientProperties();
        properties.getAmap().setEnabled(true);
        properties.getAmap().setWebServiceKey("test-key");
        properties.getAmap().setTimeoutMs(5000);
        ToolTraceService traceService = new ToolTraceService(mock(ToolCallLogRepository.class), new ObjectMapper());
        MockTools mockTools = new MockTools(mock(PoiRepository.class), mock(MockOrderRepository.class), traceService);
        AmapRouteEstimateTool tool = new AmapRouteEstimateTool(
                properties,
                mockTools,
                traceService,
                new ObjectMapper(),
                new AmapRequestLimiter(),
                new HangingHttpClient(),
                new ConcurrentMapCacheManager("route"),
                true
        );
        List<Poi> stops = List.of(
                poi("起点", 121.470, 31.230),
                poi("文化展厅", 121.490, 31.240),
                poi("云南菜餐厅", 121.505, 31.245)
        );

        long start = System.currentTimeMillis();
        Map<String, Object> route = tool.route(UUID.randomUUID(), stops);

        assertThat(System.currentTimeMillis() - start).isLessThan(3500);
        assertThat(route.get("source")).isEqualTo("mock_dynamic_route_estimate");
        assertThat(route.get("mode")).isEqualTo("mock");
    }

    private static Poi poi(String name, double lng, double lat) {
        return new Poi(name, PoiType.CULTURE, "测试", "测试地址",
                lng, lat, 60, 50, 4.6, true, false, true, true, false, false,
                "test", name);
    }

    private static class HangingHttpClient extends HttpClient {
        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            Thread.sleep(10_000);
            throw new IOException("slow route");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return new CompletableFuture<>();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return new CompletableFuture<>();
        }
    }
}
