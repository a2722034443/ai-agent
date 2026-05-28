package com.localagent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PromptCatalog {
    private static final String[] PROMPTS = {
            "project-management.prompt.md",
            "requirement-analysis.prompt.md",
            "product-planning.prompt.md",
            "route-planner.prompt.md",
            "execution.prompt.md",
            "exception-recovery.prompt.md",
            "share-feedback.prompt.md"
    };

    private final String pipelinePath;

    public PromptCatalog(@Value("${agent.pipeline:../agents/pipeline.yml}") String pipelinePath) {
        this.pipelinePath = pipelinePath;
    }

    public Map<String, String> loadPrompts() {
        Map<String, String> loaded = new LinkedHashMap<>();
        Path promptDir = resolveProjectPath("prompts");
        for (String prompt : PROMPTS) {
            Path file = promptDir.resolve(prompt);
            try {
                loaded.put(prompt, Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "missing");
            } catch (IOException e) {
                loaded.put(prompt, "unreadable: " + e.getMessage());
            }
        }
        return loaded;
    }

    public String loadPipeline() {
        Path file = resolveFromUserDir(pipelinePath);
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "missing";
        } catch (IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }

    private Path resolveFromUserDir(String path) {
        Path raw = Path.of(path);
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        Path fromUserDir = Path.of(System.getProperty("user.dir")).resolve(raw).normalize();
        if (Files.exists(fromUserDir)) {
            return fromUserDir;
        }
        Path fromParent = Path.of(System.getProperty("user.dir")).getParent();
        return fromParent == null ? fromUserDir : fromParent.resolve(raw).normalize();
    }

    private Path resolveProjectPath(String child) {
        Path userDir = Path.of(System.getProperty("user.dir"));
        Path direct = userDir.resolve(child).normalize();
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = userDir.getParent();
        return parent == null ? direct : parent.resolve(child).normalize();
    }
}
