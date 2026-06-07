package com.localagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import com.localagent.model.Poi;
import com.localagent.repo.MockOrderRepository;
import com.localagent.repo.PoiRepository;
import com.localagent.repo.ToolCallLogRepository;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
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

class AmapPoiSearchToolTest {
    @Test
    void searchPoisFallsBackQuicklyWhenParallelSearchRequestsHang() {
        ExternalClientProperties properties = new ExternalClientProperties();
        properties.getAmap().setEnabled(true);
        properties.getAmap().setWebServiceKey("test-key");
        properties.getAmap().setTimeoutMs(5000);
        ToolTraceService traceService = new ToolTraceService(mock(ToolCallLogRepository.class), new ObjectMapper());
        MockTools mockTools = new MockTools(mock(PoiRepository.class), mock(MockOrderRepository.class), traceService);
        AmapPoiSearchTool tool = new AmapPoiSearchTool(
                properties,
                mockTools,
                traceService,
                new ObjectMapper(),
                new AmapRequestLimiter(),
                new HangingHttpClient(),
                new ConcurrentMapCacheManager("poi", "geocode"),
                true
        );
        Map<String, Object> intent = Map.of(
                "location", Map.of("city", "昆明", "district", "南屏街", "lng", 102.713, "lat", 25.042),
                "poiSearchStrategy", Map.of(
                        "activityKeywords", List.of("文化展", "室内活动", "博物馆"),
                        "diningKeywords", List.of("云南菜", "餐厅", "小吃"),
                        "extraKeywords", List.of("咖啡", "书店", "公园")
                )
        );

        long start = System.currentTimeMillis();
        List<Poi> pois = tool.searchPois(UUID.randomUUID(), intent);

        assertThat(System.currentTimeMillis() - start).isLessThan(6500);
        assertThat(pois).isNotEmpty();
        assertThat(pois.get(0).getSourceProvider()).isEqualTo("city-scoped-mock");
    }

    @Test
    void searchPoisFallsBackQuicklyWhenAmapQpsLimited() {
        ExternalClientProperties properties = new ExternalClientProperties();
        properties.getAmap().setEnabled(true);
        properties.getAmap().setWebServiceKey("test-key");
        properties.getAmap().setTimeoutMs(5000);
        ToolTraceService traceService = new ToolTraceService(mock(ToolCallLogRepository.class), new ObjectMapper());
        MockTools mockTools = new MockTools(mock(PoiRepository.class), mock(MockOrderRepository.class), traceService);
        AmapPoiSearchTool tool = new AmapPoiSearchTool(
                properties,
                mockTools,
                traceService,
                new ObjectMapper(),
                new AmapRequestLimiter(),
                new QpsLimitedHttpClient(),
                new ConcurrentMapCacheManager("poi", "geocode"),
                true
        );
        Map<String, Object> intent = Map.of(
                "location", Map.of("city", "北京", "district", "北京望京", "lng", 116.480, "lat", 39.996),
                "poiSearchStrategy", Map.of(
                        "activityKeywords", List.of("酒吧", "娱乐", "文化"),
                        "diningKeywords", List.of("烧烤", "餐厅", "小吃"),
                        "extraKeywords", List.of("酒吧", "咖啡", "商场")
                )
        );

        long start = System.currentTimeMillis();
        List<Poi> pois = tool.searchPois(UUID.randomUUID(), intent);

        assertThat(System.currentTimeMillis() - start).isLessThan(2500);
        assertThat(pois).isNotEmpty();
        assertThat(pois.get(0).getSourceProvider()).isEqualTo("city-scoped-mock");
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
            throw new IOException("slow poi");
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

    private static class QpsLimitedHttpClient extends HangingHttpClient {
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new StringHttpResponse(request,
                    "{\"status\":\"0\",\"info\":\"CUQPS_HAS_EXCEEDED_THE_LIMIT\",\"infocode\":\"10021\",\"pois\":[]}");
            return response;
        }
    }

    private record StringHttpResponse(HttpRequest request, String body) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
