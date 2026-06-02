package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class SpeechTranscriptionWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SpeechTranscriptionWebSocketHandler.class);

    private final ExternalClientProperties properties;
    private final ObjectMapper objectMapper;
    private final SpeechTranscriptionService speechTranscriptionService;

    public SpeechTranscriptionWebSocketHandler(ExternalClientProperties properties,
                                               ObjectMapper objectMapper,
                                               SpeechTranscriptionService speechTranscriptionService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.speechTranscriptionService = speechTranscriptionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        StreamContext context = new StreamContext("speech_" + UUID.randomUUID(), compactId());
        session.getAttributes().put("streamContext", context);
        ExternalClientProperties.Asr asr = properties.getAsr();
        log.info("Speech stream {} opened, provider={}", context.traceId, asr.getProvider());
        if (!asr.isEnabled() || asr.getMockProvider().equalsIgnoreCase(asr.getProvider())) {
            context.mockMode = true;
            send(session, status(context, "ready", true));
            return;
        }
        if (!asr.getAliyunProvider().equalsIgnoreCase(asr.getProvider())) {
            send(session, error("Unsupported ASR provider: " + asr.getProvider()));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        String token = speechTranscriptionService.createAliyunToken(asr);
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        URI uri = URI.create(withToken(asr.getStream().getUrl(), token));
        WebSocketSession aliyun = new StandardWebSocketClient()
                .execute(new AliyunRealtimeHandler(session, context), headers,
                        uri)
                .get();
        context.aliyunSession = aliyun;
        startAliyunTranscription(aliyun, asr, context);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        StreamContext context = context(session);
        if (context.mockMode) {
            if (!context.mockFinalSent) {
                context.mockFinalSent = true;
                send(session, error("当前 ASR_PROVIDER=mock，不会识别真实语音。请确认后端已使用 ASR_PROVIDER=aliyun 重启。"));
            }
            return;
        }
        byte[] payload = toBytes(message.getPayload());
        if (payload.length == 0 || isSilent(payload)) {
            return;
        }
        context.receivedAudio.incrementAndGet();
        if (context.transcriptionReady && context.aliyunSession != null && context.aliyunSession.isOpen()) {
            sendAliyunAudio(context, payload);
            return;
        }
        context.enqueue(payload);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> body = objectMapper.readValue(message.getPayload(), new TypeReference<>() {});
        String type = String.valueOf(body.getOrDefault("type", ""));
        if ("end".equals(type)) {
            StreamContext context = context(session);
            log.info("Speech stream {} end requested, receivedAudio={}, forwardedAudio={}, pendingAudio={}",
                    context.traceId, context.receivedAudio.get(), context.forwardedAudio.get(), context.pendingAudio.size());
            if (context.aliyunSession != null && context.aliyunSession.isOpen()) {
                stopAliyunTranscription(context.aliyunSession, context);
                return;
            }
            send(session, context.chunk("", true, true, context.sequence.get() == 0));
            session.close(CloseStatus.NORMAL);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        StreamContext context = context(session);
        if (context != null && context.aliyunSession != null && context.aliyunSession.isOpen()) {
            context.aliyunSession.close();
        }
        if (context != null) {
            log.info("Speech stream {} closed, status={}, receivedAudio={}, forwardedAudio={}, resultEvents={}",
                    context.traceId, status, context.receivedAudio.get(), context.forwardedAudio.get(),
                    context.resultEvents.get());
        }
    }

    private void startAliyunTranscription(WebSocketSession session, ExternalClientProperties.Asr asr,
                                          StreamContext context) throws Exception {
        Map<String, Object> header = Map.of(
                "namespace", "SpeechTranscriber",
                "name", "StartTranscription",
                "appkey", asr.getAppKey(),
                "message_id", compactId(),
                "task_id", context.taskId
        );
        Map<String, Object> payload = Map.of(
                "format", "pcm",
                "sample_rate", asr.getSampleRate(),
                "enable_intermediate_result", true,
                "enable_punctuation_prediction", true,
                "enable_inverse_text_normalization", true
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                "header", header,
                "payload", payload
        ))));
    }

    private void stopAliyunTranscription(WebSocketSession session, StreamContext context) throws Exception {
        Map<String, Object> header = Map.of(
                "namespace", "SpeechTranscriber",
                "name", "StopTranscription",
                "appkey", properties.getAsr().getAppKey(),
                "message_id", compactId(),
                "task_id", context.taskId
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("header", header))));
    }

    private StreamContext context(WebSocketSession session) {
        return (StreamContext) session.getAttributes().get("streamContext");
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws Exception {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (IllegalStateException e) {
                log.debug("Skip speech stream message because session is already closed");
            }
        }
    }

    private Map<String, Object> error(String message) {
        return Map.of(
                "type", "error",
                "error", message,
                "timestamp", Instant.now().toEpochMilli()
        );
    }

    private Map<String, Object> status(StreamContext context, String status, boolean fallback) {
        return Map.of(
                "type", "status",
                "status", status,
                "traceId", context.traceId,
                "fallback", fallback,
                "timestamp", Instant.now().toEpochMilli()
        );
    }

    private final class AliyunRealtimeHandler extends AbstractWebSocketHandler {
        private final WebSocketSession clientSession;
        private final StreamContext context;

        private AliyunRealtimeHandler(WebSocketSession clientSession, StreamContext context) {
            this.clientSession = clientSession;
            this.context = context;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            Map<String, Object> body = objectMapper.readValue(message.getPayload(), new TypeReference<>() {});
            Map<String, Object> header = castMap(body.get("header"));
            Map<String, Object> payload = castMap(body.get("payload"));
            String name = String.valueOf(header.getOrDefault("name", ""));
            String status = String.valueOf(header.getOrDefault("status", ""));
            if (!status.isBlank() && !properties.getAsr().getSuccessStatus().equals(status)) {
                send(clientSession, error(String.valueOf(header.getOrDefault("status_message", "语音识别服务异常"))));
                clientSession.close(CloseStatus.SERVER_ERROR);
                return;
            }
            if ("TranscriptionStarted".equals(name)) {
                context.transcriptionReady = true;
                flushPendingAudio(session, context);
                log.info("Speech stream {} aliyun ready, flushedAudio={}", context.traceId,
                        context.forwardedAudio.get());
                send(clientSession, status(context, "ready", false));
                return;
            }
            String result = extractText(payload);
            boolean sentenceEnd = "SentenceEnd".equals(name);
            boolean completed = "TranscriptionCompleted".equals(name);
            boolean finalChunk = sentenceEnd || completed;
            if (!"SentenceBegin".equals(name) && !"TranscriptionCompleted".equals(name)) {
                log.info("Speech stream {} aliyun event={}, hasText={}, final={}, completed={}",
                        context.traceId, name, !result.isBlank(), finalChunk, completed);
            }
            if (!result.isBlank()) {
                context.resultEvents.incrementAndGet();
                send(clientSession, context.chunk(result, finalChunk, completed, false));
            }
            if (completed && clientSession.isOpen()) {
                if (result.isBlank()) {
                    send(clientSession, context.chunk("", true, true, context.sequence.get() == 0));
                }
                clientSession.close(CloseStatus.NORMAL);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
            if (clientSession.isOpen()) {
                send(clientSession, context.chunk("", true, true, context.sequence.get() == 0));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String extractText(Map<String, Object> payload) {
        Object result = payload.get("result");
        if (result instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null) {
                return String.valueOf(text).trim();
            }
        }
        if (result != null && !String.valueOf(result).isBlank()) {
            return String.valueOf(result).trim();
        }
        Object text = payload.get("text");
        if (text != null) {
            return String.valueOf(text).trim();
        }
        Map<String, Object> sentence = castMap(payload.get("sentence"));
        Object sentenceText = sentence.get("text");
        return sentenceText == null ? "" : String.valueOf(sentenceText).trim();
    }

    private void flushPendingAudio(WebSocketSession aliyunSession, StreamContext context) throws Exception {
        while (!context.pendingAudio.isEmpty() && aliyunSession.isOpen()) {
            sendAliyunAudio(context, context.pendingAudio.poll());
        }
    }

    private void sendAliyunAudio(StreamContext context, byte[] payload) throws Exception {
        synchronized (context.aliyunSendLock) {
            if (context.aliyunSession != null && context.aliyunSession.isOpen()) {
                context.aliyunSession.sendMessage(new BinaryMessage(payload));
                context.forwardedAudio.incrementAndGet();
            }
        }
    }

    private String withToken(String url, String token) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "token=" + token;
    }

    private byte[] toBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private boolean isSilent(byte[] payload) {
        for (byte value : payload) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class StreamContext {
        private final String traceId;
        private final String taskId;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger receivedAudio = new AtomicInteger();
        private final AtomicInteger forwardedAudio = new AtomicInteger();
        private final AtomicInteger resultEvents = new AtomicInteger();
        private final Queue<byte[]> pendingAudio = new ArrayDeque<>();
        private final Object aliyunSendLock = new Object();
        private WebSocketSession aliyunSession;
        private boolean transcriptionReady;
        private boolean mockMode;
        private boolean mockFinalSent;

        private StreamContext(String traceId, String taskId) {
            this.traceId = traceId;
            this.taskId = taskId;
        }

        private Map<String, Object> chunk(String text, boolean finalChunk, boolean completed, boolean fallback) {
            return Map.ofEntries(
                    Map.entry("type", "chunk"),
                    Map.entry("text", text),
                    Map.entry("language", "zh"),
                    Map.entry("engine", "stream"),
                    Map.entry("traceId", traceId),
                    Map.entry("sequence", sequence.incrementAndGet()),
                    Map.entry("timestamp", Instant.now().toEpochMilli()),
                    Map.entry("finalChunk", finalChunk),
                    Map.entry("completed", completed),
                    Map.entry("interim", !finalChunk && !completed),
                    Map.entry("fallback", fallback)
            );
        }

        private void enqueue(byte[] payload) {
            if (pendingAudio.size() >= 50) {
                pendingAudio.poll();
            }
            pendingAudio.offer(payload);
        }
    }
}
