package com.yooooo.rag.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.service.chat.ChatSessionService;
import com.yooooo.rag.service.metrics.TokenMetrics;
import com.yooooo.rag.service.retrieval.ConfidenceFilter;
import com.yooooo.rag.service.retrieval.ContextTrimmerService;
import com.yooooo.rag.service.retrieval.EnhancedRetrieverService;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import com.yooooo.rag.service.retrieval.RerankerService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 支持 SSE 流式输出答案，并保存聊天消息和来源。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingRagService {
    private final EnhancedRetrieverService enhancedRetriever;
    private final RerankerService rerankerService;
    private final ConfidenceFilter confidenceFilter;
    private final ContextTrimmerService contextTrimmer;
    private final SourceBuilder sourceBuilder;
    private final ChatSessionService sessionService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final TokenMetrics tokenMetrics;

    public void streamQuery(String question, List<Long> kbIds, String sessionId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        log.info("[StreamRAG] 流式问答开始 sessionId={} kbIds={} question={}", sessionId, kbIds, preview(question));

        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"RETRIEVING\",\"message\":\"正在检索知识库...\"}"));

            var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, 20);
            var reranked = rerankerService.rerank(question, candidates, 5);
            var filtered = confidenceFilter.filter(reranked);
            log.info("[StreamRAG] 检索完成 sessionId={} candidates={} reranked={} filtered={}",
                    sessionId, candidates.size(), reranked.size(), filtered.size());

            if (filtered.isEmpty()) {
                sendNotFound(emitter);
                log.info("[StreamRAG] 未找到可用上下文 sessionId={} elapsed={}ms", sessionId, System.currentTimeMillis() - start);
                return;
            }

            var trimmed = contextTrimmer.trim(filtered);
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"GENERATING\",\"message\":\"已找到相关内容，正在生成回答...\"}"));

            String context = buildContext(trimmed);
            String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size());
            StringBuilder fullAnswer = new StringBuilder();

            chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        try {
                            fullAnswer.append(token);
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(token));
                        } catch (IOException e) {
                            log.warn("[StreamRAG] SSE token 推送失败 sessionId={} reason={}", sessionId, e.getMessage());
                            throw new RuntimeException("SSE 连接断开", e);
                        }
                    })
                    .blockLast();

            String answer = fullAnswer.toString();
            int genTokens = contextTrimmer.countTokens(answer);
            tokenMetrics.recordGenerationTokens(genTokens);

            List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
            String sourcesJson = sourceBuilder.sourcesToJson(sources);
            int latencyMs = (int) (System.currentTimeMillis() - start);
            sessionService.saveMessage(sessionId, question, answer, sourcesJson, latencyMs);

            String doneData = objectMapper.writeValueAsString(new DonePayload(sources, latencyMs));
            emitter.send(SseEmitter.event().name("done").data(doneData));
            emitter.complete();

            log.info("[StreamRAG] 流式问答完成 sessionId={} sources={} answerLength={} genTokens={} elapsed={}ms",
                    sessionId, sources.size(), answer.length(), genTokens, latencyMs);
        } catch (Exception e) {
            log.error("[StreamRAG] 流式问答失败 sessionId={} kbIds={} elapsed={}ms reason={}",
                    sessionId, kbIds, System.currentTimeMillis() - start, e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"message\":\"生成失败：" + e.getMessage() + "\"}"));
                emitter.complete();
            } catch (IOException sendError) {
                log.debug("[StreamRAG] SSE error 推送失败 sessionId={} reason={}", sessionId, sendError.getMessage(), sendError);
            }
        }
    }

    public RagResponse syncQuery(String question, List<Long> kbIds, String sessionId) {
        long start = System.currentTimeMillis();
        log.info("[SyncRAG] 同步问答开始 sessionId={} kbIds={} question={}", sessionId, kbIds, preview(question));

        var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, 20);
        var reranked = rerankerService.rerank(question, candidates, 5);
        var filtered = confidenceFilter.filter(reranked);
        log.info("[SyncRAG] 检索完成 sessionId={} candidates={} reranked={} filtered={}",
                sessionId, candidates.size(), reranked.size(), filtered.size());

        if (filtered.isEmpty()) {
            log.info("[SyncRAG] 未找到可用上下文 sessionId={} elapsed={}ms", sessionId, System.currentTimeMillis() - start);
            return RagResponse.notFound();
        }

        var trimmed = contextTrimmer.trim(filtered);
        String context = buildContext(trimmed);
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size());
        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        int genTokens = contextTrimmer.countTokens(answer);
        tokenMetrics.recordGenerationTokens(genTokens);

        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
        String sourcesJson = sourceBuilder.sourcesToJson(sources);
        int latencyMs = (int) (System.currentTimeMillis() - start);
        sessionService.saveMessage(sessionId, question, answer, sourcesJson, latencyMs);

        log.info("[SyncRAG] 同步问答完成 sessionId={} sources={} answerLength={} genTokens={} elapsed={}ms",
                sessionId, sources.size(), answer.length(), genTokens, latencyMs);
        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs(latencyMs)
                .build();
    }

    private String buildContext(List<HybridRetrieverService.ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            var sc = chunks.get(i);
            sb.append("[参考").append(i + 1).append("]");
            if (sc.chunk().getSectionTitle() != null) {
                sb.append(" ").append(sc.chunk().getSectionTitle());
            }
            sb.append("\n").append(sc.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private void sendNotFound(SseEmitter emitter) throws IOException {
        String msg = "在知识库中未找到与该问题相关的内容。请尝试用不同关键词提问，或联系相关部门。";
        emitter.send(SseEmitter.event().name("token").data(msg));
        emitter.send(SseEmitter.event().name("done").data("{\"sources\":[]}"));
        emitter.complete();
    }

    private String preview(String question) {
        if (question == null) {
            return "";
        }
        return question.substring(0, Math.min(60, question.length()));
    }

    /**
     * 流式回答结束时推送给前端的汇总数据。
     */
    record DonePayload(List<RagResponse.Source> sources, int latencyMs) {}
}
