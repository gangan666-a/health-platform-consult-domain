package com.example.mall.agentservice.config;

public final class AgentLlmSupport {

    private AgentLlmSupport() {
    }

    public static boolean isLlmEnabled(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return !apiKey.startsWith("placeholder");
    }
}
