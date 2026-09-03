package com.example.mall.agentservice.config;

import com.example.mall.agentservice.orchestration.AgentSessionStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentOrchestrationProperties.class)
public class AgentAiConfig {

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(40).build();
    }

    @Bean
    ChatClient.Builder chatClientBuilder(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
    }

    /**
     * 各 Sub Agent 共享的 ChatClient 工厂：按 Agent 类型注入独立 system prompt。
     */
    @Bean
    AgentChatClientFactory agentChatClientFactory(ChatModel chatModel, ChatMemory chatMemory,
                                                    AgentSessionStore sessionStore) {
        return new AgentChatClientFactory(chatModel, chatMemory, sessionStore);
    }
}
