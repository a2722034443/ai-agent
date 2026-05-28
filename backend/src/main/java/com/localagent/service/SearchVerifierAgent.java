package com.localagent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SearchVerifierAgent {
    private final WebSearchTool webSearchTool;

    public SearchVerifierAgent(WebSearchTool webSearchTool) {
        this.webSearchTool = webSearchTool;
    }

    public List<Map<String, Object>> verify(UUID planId, Map<String, Object> intent, String message) {
        return webSearchTool.verifyPlanningContext(planId, intent, message);
    }
}
