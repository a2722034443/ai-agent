package com.localagent;

import com.localagent.config.ExternalClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ExternalClientProperties.class)
public class LocalLifeAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(LocalLifeAgentApplication.class, args);
    }
}
