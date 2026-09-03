package com.example.mall.agentservice.config;

import com.example.mall.agentservice.orchestration.AgentSessionStore;
import com.example.mall.common.model.AgentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class AgentChatClientFactory {

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final AgentSessionStore sessionStore;

    public AgentChatClientFactory(ChatModel chatModel, ChatMemory chatMemory, AgentSessionStore sessionStore) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.sessionStore = sessionStore;
    }

    public ChatClient forAgent(AgentType type, String systemPrompt) {
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public static String memoryKey(String sessionId, AgentType type) {
        return sessionId + ":" + type.name();
    }

    public ChatClient.Builder newBuilder() {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
    }

    public AgentSessionStore sessionStore() {
        return sessionStore;
    }
}
