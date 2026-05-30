package com.localagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external")
public class ExternalClientProperties {
    private final Amap amap = new Amap();
    private final Search search = new Search();
    private final Llm llm = new Llm();

    public Amap getAmap() {
        return amap;
    }

    public Search getSearch() {
        return search;
    }

    public Llm getLlm() {
        return llm;
    }

    public static class Amap {
        private boolean enabled = true;
        private String baseUrl = "https://restapi.amap.com";
        private String webServiceKey = "";
        private String jsApiKey = "";
        private String jsSecurityCode = "";
        private String city = "";
        private String defaultOrigin = "";
        private int timeoutMs = 3000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getWebServiceKey() { return webServiceKey; }
        public void setWebServiceKey(String webServiceKey) { this.webServiceKey = webServiceKey; }
        public String getJsApiKey() { return jsApiKey; }
        public void setJsApiKey(String jsApiKey) { this.jsApiKey = jsApiKey; }
        public String getJsSecurityCode() { return jsSecurityCode; }
        public void setJsSecurityCode(String jsSecurityCode) { this.jsSecurityCode = jsSecurityCode; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getDefaultOrigin() { return defaultOrigin; }
        public void setDefaultOrigin(String defaultOrigin) { this.defaultOrigin = defaultOrigin; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Search {
        private boolean enabled = true;
        private String provider = "tavily";
        private String baseUrl = "https://api.tavily.com";
        private String tavilyApiKey = "";
        private int maxResults = 5;
        private int timeoutMs = 3000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getTavilyApiKey() { return tavilyApiKey; }
        public void setTavilyApiKey(String tavilyApiKey) { this.tavilyApiKey = tavilyApiKey; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Llm {
        private boolean enabled = true;
        private String provider = "mimo";
        private String apiKey = "";
        private String baseUrl = "https://token-plan-cn.xiaomimimo.com/v1";
        private String model = "mimo-v2.5-pro";
        private int maxTokens = 4096;
        private double temperature = 0.1;
        private int timeoutMs = 30000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
