package com.localagent.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient amapHttpClient(ExternalClientProperties properties) {
        int timeout = properties.getAmap().getTimeoutMs();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    public HttpClient llmHttpClient(ExternalClientProperties properties) {
        int timeout = properties.getLlm().getTimeoutMs();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
