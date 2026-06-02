package com.localagent.config;

import com.localagent.service.SpeechTranscriptionWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SpeechWebSocketConfig implements WebSocketConfigurer {
    private final SpeechTranscriptionWebSocketHandler speechTranscriptionWebSocketHandler;

    @Value("${app.frontend-origin:http://localhost:5173,http://127.0.0.1:5173}")
    private String frontendOrigins;

    public SpeechWebSocketConfig(SpeechTranscriptionWebSocketHandler speechTranscriptionWebSocketHandler) {
        this.speechTranscriptionWebSocketHandler = speechTranscriptionWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(speechTranscriptionWebSocketHandler, "/api/speech/transcribe/stream")
                .setAllowedOrigins(parseOrigins());
    }

    private String[] parseOrigins() {
        return java.util.Arrays.stream(frontendOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }
}
