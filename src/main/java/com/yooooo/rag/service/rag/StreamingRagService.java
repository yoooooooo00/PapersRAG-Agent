package com.yooooo.rag.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.service.chat.ChatSessionService;
import com.yooooo.rag.service.metrics.TokenMetrics;
import com.yooooo.rag.service.retrieval.ConfidenceFilter;
import com.yooooo.rag.service.retrieval.ContextTrimmerService;
import com.yooooo.rag.service.retrieval.EnhancedRetrieverService;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import com.yooooo.rag.service.retrieval.QueryRoutingService;
import com.yooooo.rag.service.retrieval.RerankerService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streaming paper QA with the same routing and retrieval strategy as sync QA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingRagService {
    private final HybridRetrieverService hybridRetriever;
    private final EnhancedRetrieverService enhancedRetriever;
    private final QueryRoutingService queryRoutingService;
    private final RerankerService rerankerService;
    private final ConfidenceFilter confidenceFilter;
    private final ContextTrimmerService contextTrimmer;
    private final RagContextBuilder contextBuilder;
    private final SourceBuilder sourceBuilder;
    private final ChatSessionService sessionService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final TokenMetrics tokenMetrics;
    private final FullRagPipeline fullRagPipeline;

    @Value("${reranker.top-n:5}")
    private int rerankerTopN;

    @Value("${rag.routing.simple-top-n:5}")
    private int simpleTopN;

    @Value("${rag.routing.standard-top-n:10}")
    private int standardTopN;

    @Value("${rag.routing.complex-candidate-top-n:20}")
    private int complexCandidateTopN;

    @Value("${rag.routing.simple-as-standard-experiment:false}")
    private boolean simpleAsStandardExperiment;

    public void streamQuery(String question, List<Long> kbIds, String sessionId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        RagResponse cached = fullRagPipeline.getCached(question, kbIds);
        if (cached != null) {
            try {
                emitter.send(SseEmitter.event().name("token").data(cached.getAnswer()));
                emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(new DonePayload(cached.getSources(), 0))));
                emitter.complete();
            } catch (IOException e) { emitter.completeWithError(e); }
            return;
        }
        FullRagPipeline.QueryPlan plan = fullRagPipeline.prepare(question, kbIds);
        QueryRoutingService.QueryRoute route = plan.route();
        log.info("[StreamRAG] start sessionId={} route={} kbIds={} question={}", sessionId, route, kbIds, preview(question));

        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"RETRIEVING\",\"message\":\"Retrieving knowledge base...\"}"));

            var candidates = plan.candidates();
            if (candidates.isEmpty()) {
                sendNotFound(emitter);
                log.info("[StreamRAG] no context sessionId={} elapsed={}ms", sessionId, System.currentTimeMillis() - start);
                return;
            }

            var filtered = candidates;
            log.info("[StreamRAG] retrieval done sessionId={} candidates={} filtered={}",
                    sessionId, candidates.size(), filtered.size());

            if (filtered.isEmpty()) {
                sendNotFound(emitter);
                log.info("[StreamRAG] no useful context sessionId={} elapsed={}ms", sessionId, System.currentTimeMillis() - start);
                return;
            }

            var trimmed = plan.trimmed();
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"GENERATING\",\"message\":\"Generating answer...\"}"));

            String context = plan.context();
            String systemPrompt = RagPromptTemplate.buildSystemPrompt(question, context, trimmed.size(), route);
            StringBuilder fullAnswer = new StringBuilder();

            chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        try {
                            fullAnswer.append(token);
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            log.warn("[StreamRAG] SSE token send failed sessionId={} reason={}", sessionId, e.getMessage());
                            throw new RuntimeException("SSE connection closed", e);
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

            log.info("[StreamRAG] done sessionId={} route={} sources={} answerLength={} genTokens={} elapsed={}ms",
                    sessionId, route, sources.size(), answer.length(), genTokens, latencyMs);
        } catch (Exception e) {
            log.error("[StreamRAG] failed sessionId={} kbIds={} elapsed={}ms reason={}",
                    sessionId, kbIds, System.currentTimeMillis() - start, e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"message\":\"Generation failed: " + escapeJson(e.getMessage()) + "\"}"));
                emitter.complete();
            } catch (IOException sendError) {
                log.debug("[StreamRAG] SSE error send failed sessionId={} reason={}", sessionId, sendError.getMessage(), sendError);
            }
        }
    }

    public RagResponse syncQuery(String question, List<Long> kbIds, String sessionId) {
        long start = System.currentTimeMillis();
        FullRagPipeline.QueryPlan plan = fullRagPipeline.prepare(question, kbIds);
        QueryRoutingService.QueryRoute route = plan.route();
        log.info("[SyncRAG] start sessionId={} route={} kbIds={} question={}", sessionId, route, kbIds, preview(question));

        var candidates = plan.candidates();
        if (candidates.isEmpty()) {
            return RagResponse.builder()
                    .answer("当前知识库中没有找到与该问题相关的可用内容。建议你换个关键词，或者把问题问得更具体一点。")
                    .sources(List.of())
                    .queryRoute(route)
                    .retrievedChunkIds(new Long[0])
                    .trimmedChunkIds(new Long[0])
                    .notFound(true)
                    .build();
        }

        var filtered = candidates;
        log.info("[SyncRAG] retrieval done sessionId={} candidates={} filtered={}",
                sessionId, candidates.size(), filtered.size());

        if (filtered.isEmpty()) {
            return RagResponse.builder()
                    .answer("当前知识库中没有找到与该问题相关的可用内容。建议你换个关键词，或者把问题问得更具体一点。")
                    .sources(List.of())
                    .queryRoute(route)
                    .retrievedChunkIds(new Long[0])
                    .trimmedChunkIds(new Long[0])
                    .notFound(true)
                    .build();
        }

        var trimmed = plan.trimmed();
        String context = plan.context();
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(question, context, trimmed.size(), route);
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

        log.info("[SyncRAG] done sessionId={} route={} sources={} answerLength={} genTokens={} elapsed={}ms",
                sessionId, route, sources.size(), answer.length(), genTokens, latencyMs);
        RagResponse response = RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs(latencyMs)
                .queryRoute(route)
                .retrievedChunkIds(candidates.stream()
                        .map(HybridRetrieverService.ScoredChunk::id)
                        .distinct()
                        .toArray(Long[]::new))
                .trimmedChunkIds(trimmed.stream()
                        .map(HybridRetrieverService.ScoredChunk::id)
                        .distinct()
                        .toArray(Long[]::new))
                .build();
        fullRagPipeline.cache(question, kbIds, response);
        return response;
    }

    private void sendNotFound(SseEmitter emitter) throws IOException {
        String msg = "No relevant content was found in the knowledge base.";
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

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Payload returned when the streaming response finishes.
     */
    record DonePayload(List<RagResponse.Source> sources, int latencyMs) {}
}
