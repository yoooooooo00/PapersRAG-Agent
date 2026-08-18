package com.yooooo.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 Spring AI 的聊天客户端，统一接入大模型能力。
 */
@Configuration
public class SpringAiConfig {
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }
}
