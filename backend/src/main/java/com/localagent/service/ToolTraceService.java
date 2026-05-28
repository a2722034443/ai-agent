package com.localagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.model.ToolCallLog;
import com.localagent.repo.ToolCallLogRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ToolTraceService {
    private final ToolCallLogRepository toolCallLogRepository;
    private final ObjectMapper objectMapper;

    public ToolTraceService(ToolCallLogRepository toolCallLogRepository, ObjectMapper objectMapper) {
        this.toolCallLogRepository = toolCallLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trace(UUID planId, String toolName, String status, long start, Object input, Object output) {
        toolCallLogRepository.save(new ToolCallLog(
                planId,
                toolName,
                status,
                System.currentTimeMillis() - start,
                toJson(input),
                toJson(output)
        ));
    }

    public Map<String, Object> externalMeta(String provider, String mode, String sourceUrl, String externalStatus) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", provider);
        meta.put("mode", mode);
        meta.put("sourceUrl", sourceUrl);
        meta.put("externalStatus", externalStatus);
        return meta;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
