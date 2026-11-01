package com.example.mall.agentservice.config;

import com.example.mall.agentservice.orchestration.AgentSessionStore;
import com.example.mall.agentservice.repository.ChatMessageRecordRepository;
import com.example.mall.agentservice.service.PersistentChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentOrchestrationProperties.class)
public class AgentAiConfig {

    /**
     * L0 会话记忆：对话原文全量落库（chat_message 表），重启不丢、可还原；
     * 注入仍按窗口控制体积（最近 N 条，默认 40），超窗部分留库供交接纪要/历史还原使用。
     */
    @Bean
    ChatMemory chatMemory(ChatMessageRecordRepository chatMessageRepository,
                          @Value("${agent.memory.window:40}") int memoryWindow) {
        return new PersistentChatMemory(chatMessageRepository, memoryWindow);
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
