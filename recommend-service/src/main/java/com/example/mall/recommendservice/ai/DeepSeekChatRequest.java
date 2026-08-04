package com.example.mall.recommendservice.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek Chat Completions 请求体（OpenAI 兼容格式）。
 * 仅暴露本服务用到的字段；thinking 显式关闭以保证 temperature 生效、响应只出现在 content。
 */
public class DeepSeekChatRequest {

    private String model;

    private List<Message> messages;

    private double temperature;

    /** {"type":"disabled"}：关闭思考模式（v4 系列默认开启，必须显式关掉） */
    private Map<String, Object> thinking;

    /** {"type":"json_object"}：强制 JSON 输出 */
    @JsonProperty("response_format")
    private Map<String, Object> responseFormat;

    @JsonProperty("max_tokens")
    private int maxTokens;

    private boolean stream;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public Map<String, Object> getThinking() {
        return thinking;
    }

    public void setThinking(Map<String, Object> thinking) {
        this.thinking = thinking;
    }

    public Map<String, Object> getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(Map<String, Object> responseFormat) {
        this.responseFormat = responseFormat;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public static class Message {
        private String role;
        private String content;

        public Message() {
        }

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
