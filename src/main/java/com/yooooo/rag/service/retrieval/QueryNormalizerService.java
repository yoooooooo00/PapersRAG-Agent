package com.yooooo.rag.service.retrieval;

import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 把用户问题改写成更稳定、适合检索的标准表达。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryNormalizerService {
    private final ChatClient chatClient;

    @Cacheable(value = "query-normalize-cache", key = "#question.hashCode()")
    public String normalize(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        try {
            String prompt = """
                    请将用户问题改写为适合知识库缓存命中的标准问法。

                    要求：
                    1. 保留原始问题的核心意图、关键实体、约束条件和时间/数量条件。
                    2. 去掉口语化、寒暄、无意义语气词和重复表达。
                    3. 将同义问法统一成稳定表达，例如“咋办/怎么处理/如何处理”统一为“如何处理”。
                    4. 不要扩展问题，不要补充用户没有给出的条件。
                    5. 只输出改写后的问题，不要解释。

                    用户问题：%s
                    """.formatted(question.strip());

            String normalized = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().temperature(0.0).build())
                    .user(prompt)
                    .call()
                    .content();
            normalized = cleanup(normalized);
            if (normalized.isBlank()) {
                return question.strip();
            }
            log.debug("[QueryNormalizer] original={} normalized={}", preview(question), preview(normalized));
            return normalized;
        } catch (Exception e) {
            log.warn("[QueryNormalizer] normalize failed, using original question: {}", e.getMessage());
            return question.strip();
        }
    }
    private String cleanup(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.strip();
        cleaned = QUOTE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\s+", " ").strip();
        return cleaned;
    }
    private String preview(String value) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(40, value.length()));
    }
    private static final Pattern QUOTE_PATTERN = Pattern.compile("^[\\\"'“”‘’]+|[\\\"'“”‘’]+$");
}
