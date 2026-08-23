package com.yooooo.rag.service.retrieval;

import com.yooooo.rag.service.metrics.TokenMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 使用大模型生成更适合检索的查询改写结果。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryRewriterService {
    private final ChatClient chatClient;
    private final TokenMetrics tokenMetrics;
    private final ContextTrimmerService contextTrimmer;

    @Cacheable(value = "hyde-cache", key = "#question")
    public String generateHypotheticalAnswer(String question) {
        log.debug("[QueryRewriter] HyDE 生成假设回答：{}", question);

        String prompt = """
                请根据以下问题，生成一个简洁的假设性回答（2-4句话）。
                这个回答不需要准确，只需要在语义上覆盖可能的答案内容。
                直接给出答案内容，不要任何前缀说明。

                问题：%s
                """.formatted(question);
        try {
            String hypothetical = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            log.debug("[QueryRewriter] HyDE 结果：{}", hypothetical);

            int genTokens = contextTrimmer.countTokens(hypothetical);
            tokenMetrics.recordGenerationTokens(genTokens);

            return hypothetical;
        } catch (Exception e) {
            log.warn("[QueryRewriter] HyDE 生成失败，使用原始问题：{}", e.getMessage());
            return question;
        }
    }

    public List<String> expandQuery(String question) {
        log.debug("[QueryRewriter] 多路扩展：{}", question);

        String prompt = """
                请将以下问题改写成3个不同表达方式的查询，要求：
                1. 保持原始意图不变
                2. 每个查询角度略有不同（换词、换句式、从不同维度提问）
                3. 每行一个查询，不要编号，不要任何额外说明

                原始问题：%s
                """.formatted(question);

        try {
            String expanded = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            int genTokens = contextTrimmer.countTokens(expanded);
            tokenMetrics.recordGenerationTokens(genTokens);

            List<String> queries = new ArrayList<>();
            queries.add(question);

            Arrays.stream(expanded.split("\n"))
                    .map(String::strip)
                    .filter(q -> !q.isBlank() && !q.equals(question))
                    .limit(3)
                    .forEach(queries::add);

            log.debug("[QueryRewriter] 扩展结果：{}", queries);
            return queries;

        } catch (Exception e) {
            log.warn("[QueryRewriter] 查询扩展失败，使用原始问题：{}", e.getMessage());
            return List.of(question);
        }
    }
}
