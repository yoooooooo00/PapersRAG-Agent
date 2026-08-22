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

    @Value("${reranker.top-n:5}")
    private int rerankerTopN;

    @Value("${rag.routing.simple-top-n:5}")
    private int simpleTopN;

    @Value("${rag.routing.standard-top-n:10}")
    private int standardTopN;

    @Value("${rag.routing.complex-candidate-top-n:20}")
    private int complexCandidateTopN;

    public void streamQuery(String question, List<Long> kbIds, String sessionId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        QueryRoutingService.QueryRoute route = queryRoutingService.classify(question);
        log.info("[StreamRAG] start sessionId={} route={} kbIds={} question={}", sessionId, route, kbIds, preview(question));

        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"RETRIEVING\",\"message\":\"Retrieving knowledge base...\"}"));

            var candidates = retrieveByRoute(route, question, kbIds);
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

            var trimmed = contextTrimmer.trim(filtered);
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"GENERATING\",\"message\":\"Generating answer...\"}"));

            String context = contextBuilder.buildContext(trimmed);
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
        QueryRoutingService.QueryRoute route = queryRoutingService.classify(question);
        log.info("[SyncRAG] start sessionId={} route={} kbIds={} question={}", sessionId, route, kbIds, preview(question));

        var candidates = retrieveByRoute(route, question, kbIds);
        if (candidates.isEmpty()) {
            return RagResponse.notFound();
        }

        var filtered = candidates;
        log.info("[SyncRAG] retrieval done sessionId={} candidates={} filtered={}",
                sessionId, candidates.size(), filtered.size());

        if (filtered.isEmpty()) {
            return RagResponse.notFound();
        }

        var trimmed = contextTrimmer.trim(filtered);
        String context = contextBuilder.buildContext(trimmed);
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
        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs(latencyMs)
                .build();
    }

    private List<HybridRetrieverService.ScoredChunk> retrieveByRoute(
            QueryRoutingService.QueryRoute route,
            String question,
            List<Long> kbIds) {
        return switch (route) {
            case SIMPLE -> hybridRetriever.retrieveVectorOnly(question, kbIds, simpleTopN);
            case STANDARD -> hybridRetriever.retrieve(question, kbIds, standardTopN);
            case COMPLEX -> {
                var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, complexCandidateTopN);
                yield rerankerService.rerank(question, candidates, rerankerTopN);
            }
        };
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
