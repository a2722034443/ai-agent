package com.localagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.config.ExternalClientProperties;
import com.localagent.dto.ApiDtos.SpeechTranscribeResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SpeechTranscriptionService {
    private static final DateTimeFormatter ISO_8601 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String ALIYUN_TOKEN_ACTION = "CreateToken";
    private static final String ALIYUN_TOKEN_VERSION = "2019-02-28";

    private final ExternalClientProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SpeechTranscriptionService(ExternalClientProperties properties,
                                      ObjectMapper objectMapper,
                                      @Qualifier("asrHttpClient") HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public SpeechTranscribeResponse transcribe(MultipartFile file) {
        long start = System.currentTimeMillis();
        String traceId = "speech_" + UUID.randomUUID();
        ExternalClientProperties.Asr asr = properties.getAsr();
        validate(file, asr);
        if (!asr.isEnabled() || "mock".equalsIgnoreCase(asr.getProvider())) {
            return new SpeechTranscribeResponse("今天晚上七点在上海静安寺附近，四个朋友，预算八百，想先玩再吃饭",
                    "zh", elapsed(start), "mock", traceId);
        }
        if (!"aliyun".equalsIgnoreCase(asr.getProvider())) {
            throw new IllegalArgumentException("Unsupported ASR provider: " + asr.getProvider());
        }
        if (isBlank(asr.getAccessKeyId()) || isBlank(asr.getAccessKeySecret()) || isBlank(asr.getAppKey())) {
            throw new IllegalArgumentException("Aliyun ASR credentials are incomplete");
        }
        try {
            String token = createAliyunToken(asr);
            String text = transcribeWithAliyun(file, asr, token);
            return new SpeechTranscribeResponse(text, "zh", elapsed(start), "aliyun", traceId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new SpeechTranscriptionException("语音识别失败，请重试或直接输入文字", e);
        }
    }

    private void validate(MultipartFile file, ExternalClientProperties.Asr asr) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("音频文件不能为空");
        }
        long maxBytes = Math.max(1, asr.getMaxAudioMb()) * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("音频文件过大，请控制在 " + asr.getMaxAudioMb() + "MB 以内");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean allowed = filename.endsWith(".wav") || filename.endsWith(".pcm") || filename.endsWith(".mp3")
                || filename.endsWith(".m4a") || filename.endsWith(".webm")
                || contentType.contains("audio/") || contentType.contains("octet-stream");
        if (!allowed) {
            throw new IllegalArgumentException("仅支持 wav、pcm、mp3、m4a 或 webm 音频");
        }
    }

    private String createAliyunToken(ExternalClientProperties.Asr asr) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("AccessKeyId", asr.getAccessKeyId());
        params.put("Action", ALIYUN_TOKEN_ACTION);
        params.put("Format", "JSON");
        params.put("RegionId", "cn-shanghai");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", ISO_8601.format(Instant.now()));
        params.put("Version", ALIYUN_TOKEN_VERSION);

        String canonical = canonicalizedQuery(params);
        String stringToSign = "GET&%2F&" + percentEncode(canonical);
        String signature = hmacSha1(stringToSign, asr.getAccessKeySecret() + "&");
        String uri = trimTrailingSlash(asr.getTokenUrl()) + "/?"
                + canonical + "&Signature=" + percentEncode(signature);

        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofMillis(asr.getTimeoutMs()))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        if (response.statusCode() >= 400 || body.containsKey("Code")) {
            throw new IllegalStateException("Aliyun token request failed: " + body);
        }
        Map<String, Object> token = castMap(body.get("Token"));
        String id = String.valueOf(token.getOrDefault("Id", ""));
        if (isBlank(id)) {
            throw new IllegalStateException("Aliyun token response did not include Token.Id");
        }
        return id;
    }

    private String transcribeWithAliyun(MultipartFile file, ExternalClientProperties.Asr asr, String token) throws Exception {
        String url = trimTrailingSlash(asr.getBaseUrl())
                + "/stream/v1/asr?appkey=" + percentEncode(asr.getAppKey())
                + "&format=" + percentEncode(asr.getFormat())
                + "&sample_rate=" + asr.getSampleRate()
                + "&enable_punctuation_prediction=true"
                + "&enable_inverse_text_normalization=true";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(asr.getTimeoutMs()))
                .header("X-NLS-Token", token)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(file.getBytes()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        String status = String.valueOf(body.getOrDefault("status", ""));
        if (response.statusCode() >= 400 || !"20000000".equals(status)) {
            throw new IllegalStateException("Aliyun ASR request failed: " + body);
        }
        String result = String.valueOf(body.getOrDefault("result", "")).trim();
        if (result.isBlank()) {
            throw new IllegalStateException("Aliyun ASR returned empty text");
        }
        return result;
    }

    private String canonicalizedQuery(Map<String, String> params) {
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String hmacSha1(String text, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
