package com.yooooo.rag.service.rag;

import com.yooooo.rag.service.metrics.TokenMetrics;
import com.yooooo.rag.service.retrieval.ContextTrimmerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 对生成答案做忠实度检查，发现可能脱离上下文的回答。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HallucinationChecker {
    private final ChatClient chatClient;
    private final TokenMetrics tokenMetrics;
    private final ContextTrimmerService contextTrimmer;
/**
 * 忠实度检查结果，包含是否可信、分数和原因。
 */

    public record FaithfulnessResult(
            boolean isFaithful,
            double score,
            String reason
    ) {}
    public FaithfulnessResult check(String question, String answer, String context) {
        String prompt = """
                请判断以下【答案】是否忠实于【参考内容】（即答案中的事实是否都能在参考内容中找到依据）。

                【问题】：%s

                【参考内容】：
                %s

                【答案】：
                %s

                请回答以下内容（格式严格按照示例）：
                忠实性分数：[0-10的整数，10=完全忠实，0=完全不忠实]
                是否忠实：[是/否]
                理由：[一句话解释]
                """.formatted(question, context, answer);

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            int genTokens = contextTrimmer.countTokens(response);
            tokenMetrics.recordGenerationTokens(genTokens);

            return parseResult(response);

        } catch (Exception e) {
            log.warn("[HallucinationChecker] 检测失败，默认通过：{}", e.getMessage());
            return new FaithfulnessResult(true, 0.5, "检测失败，默认通过");
        }
    }

    private FaithfulnessResult parseResult(String response) {
        double score = 0.5;
        boolean faithful = true;
        String reason = "";

        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.startsWith("忠实性分数：")) {
                try {
                    String numStr = line.replace("忠实性分数：", "").replaceAll("[^0-9]", "").strip();
                    score = Double.parseDouble(numStr) / 10.0;
                } catch (NumberFormatException ignored) {}
            } else if (line.startsWith("是否忠实：")) {
                String value = line.replace("是否忠实：", "").strip();
                faithful = "是".equals(value);
            } else if (line.startsWith("理由：")) {
                reason = line.replace("理由：", "").strip();
            }
        }

        return new FaithfulnessResult(faithful, score, reason);
    }
}
