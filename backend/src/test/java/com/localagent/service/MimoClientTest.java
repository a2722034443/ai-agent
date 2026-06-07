package com.localagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class MimoClientTest {

    @Test
    void fallsBackToSecondaryWhenPrimaryFails() {
        ExternalClientProperties properties = properties();
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(
                response(500, "{}"),
                response(200, """
                        {"choices":[{"message":{"content":"{\\"scenario\\":\\"friends\\"}"}}]}
                        """)
        ));
        MimoClient client = new MimoClient(properties, new ObjectMapper(), httpClient);

        MimoClient.CompletionResult result = client.completeWithMeta("system", "user");

        assertThat(result.content()).contains("friends");
        assertThat(result.responseSource()).isEqualTo("content");
        assertThat(result.lane()).isEqualTo("secondary");
        assertThat(result.fallbackReason()).contains("primary");
        assertThat(httpClient.apiKeys).containsExactly("primary-key", "secondary-key");
    }

    @Test
    void fallsBackToSecondaryWhenPrimaryTimesOut() {
        ExternalClientProperties properties = properties();
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(
                new IOException("timeout"),
                response(200, """
                        {"choices":[{"message":{"content":"{\\"scenario\\":\\"friends\\"}"}}]}
                        """)
        ));
        MimoClient client = new MimoClient(properties, new ObjectMapper(), httpClient);

        MimoClient.CompletionResult result = client.completeWithMeta("system", "user");

        assertThat(result.content()).contains("friends");
        assertThat(result.responseSource()).isEqualTo("content");
        assertThat(result.lane()).isEqualTo("secondary");
        assertThat(result.failures()).anyMatch(reason -> reason.contains("primary"));
        assertThat(httpClient.apiKeys).containsExactly("primary-key", "secondary-key");
    }

    @Test
    void reportsBothFailuresWhenNoLaneWorks() {
        ExternalClientProperties properties = properties();
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(
                response(500, "{}"),
                response(429, "{}")
        ));
        MimoClient client = new MimoClient(properties, new ObjectMapper(), httpClient);

        assertThatThrownBy(() -> client.completeWithMeta("system", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary")
                .hasMessageContaining("secondary");
    }

    @Test
    void usesReasoningContentWhenContentIsBlank() {
        ExternalClientProperties properties = properties();
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(
                response(200, """
                        {"choices":[{"finish_reason":"stop","message":{"content":"","reasoning_content":"{\\"scenario\\":\\"friends\\"}"}}]}
                        """)
        ));
        MimoClient client = new MimoClient(properties, new ObjectMapper(), httpClient);

        MimoClient.CompletionResult result = client.completeWithMeta("system", "user");

        assertThat(result.content()).contains("friends");
        assertThat(result.reasoningContent()).contains("friends");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.responseSource()).isEqualTo("reasoning_content");
    }

    @Test
    void reportsFinishReasonWhenBothContentAndReasoningContentAreBlank() {
        ExternalClientProperties properties = properties();
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(
                response(200, """
                        {"choices":[{"finish_reason":"length","message":{"content":"","reasoning_content":""}}]}
                        """)
        ));
        MimoClient client = new MimoClient(properties, new ObjectMapper(), httpClient);

        assertThatThrownBy(() -> client.completeWithMeta("system", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finish_reason=length")
                .hasMessageContaining("contentBlank=true")
                .hasMessageContaining("reasoningContentBlank=true");
    }

    private ExternalClientProperties properties() {
        ExternalClientProperties properties = new ExternalClientProperties();
        properties.getLlm().setApiKey("primary-key");
        properties.getLlm().setBaseUrl("https://primary.example/v1");
        properties.getLlm().setModel("primary-model");
        properties.getLlm().setMaxTokens(512);
        properties.getLlm().setTimeoutMs(100);
        properties.getLlm().getSecondary().setApiKey("secondary-key");
        properties.getLlm().getSecondary().setBaseUrl("https://secondary.example/v1");
        properties.getLlm().getSecondary().setModel("secondary-model");
        properties.getLlm().getSecondary().setMaxTokens(512);
        properties.getLlm().getSecondary().setTimeoutMs(100);
        return properties;
    }

    private static HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            @Override public String body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://example.test"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static class RecordingHttpClient extends HttpClient {
        private final List<Object> responses;
        private final List<String> apiKeys = new ArrayList<>();
        private int index;

        RecordingHttpClient(List<?> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            apiKeys.add(request.headers().firstValue("api-key").orElse(""));
            Object next = responses.get(index++);
            if (next instanceof IOException ioException) {
                throw ioException;
            }
            if (next instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) next;
            return response;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    }
}
