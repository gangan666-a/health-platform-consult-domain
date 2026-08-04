package com.example.mall.recommendservice.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek Chat Completions 响应体（只关心 choices[0].message.content）。
 * 注意：thinking 模式下 reasoning_content 与 content 同级，本服务关闭了 thinking，
 * 且解析一律只取 content、忽略 reasoning_content。
 */
public class DeepSeekChatResponse {

    private List<Choice> choices;

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public static class Choice {
        private Message message;

        public Message getMessage() {
            return message;
        }

        public void setMessage(Message message) {
            this.message = message;
        }
    }

    public static class Message {
        private String content;

        @JsonProperty("reasoning_content")
        private String reasoningContent;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getReasoningContent() {
            return reasoningContent;
        }

        public void setReasoningContent(String reasoningContent) {
            this.reasoningContent = reasoningContent;
        }
    }
}
