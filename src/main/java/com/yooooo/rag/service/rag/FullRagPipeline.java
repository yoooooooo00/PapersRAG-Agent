package com.yooooo.rag.service.rag;

import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.service.retrieval.ConfidenceFilter;
import com.yooooo.rag.service.retrieval.ContextTrimmerService;
import com.yooooo.rag.service.retrieval.EnhancedRetrieverService;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import com.yooooo.rag.service.retrieval.QueryCacheService;
import com.yooooo.rag.service.retrieval.QueryNormalizerService;
import com.yooooo.rag.service.retrieval.QueryRoutingService;
import com.yooooo.rag.service.retrieval.RerankerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Full paper QA pipeline with routing, retrieval, reranking, and source generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FullRagPipeline {
    private final HybridRetrieverService hybridRetriever;
    private final EnhancedRetrieverService enhancedRetriever;
    private final RerankerService rerankerService;
    private final ConfidenceFilter confidenceFilter;
    private final ContextTrimmerService contextTrimmer;
    private final SourceBuilder sourceBuilder;
    private final HallucinationChecker hallucinationChecker;
    private final QueryRoutingService queryRoutingService;
    private final QueryNormalizerService queryNormalizerService;
    private final QueryCacheService queryCacheService;
    private final ChatClient chatClient;

    @Value("${reranker.top-n:5}")
    private int rerankerTopN;

    @Value("${rag.routing.simple-top-n:5}")
    private int simpleTopN;

    @Value("${rag.routing.standard-top-n:10}")
    private int standardTopN;

    @Value("${rag.routing.complex-candidate-top-n:20}")
    private int complexCandidateTopN;

    public RagResponse query(String question, List<Long> kbIds) {
        long pipelineStart = System.currentTimeMillis();
        String cacheQuestion = queryNormalizerService.normalize(question);
        RagResponse cached = queryCacheService.getFromCache(cacheQuestion, kbIds);
        if (cached != null) {
            cached.setLatencyMs((int) (System.currentTimeMillis() - pipelineStart));
            return cached;
        }

        QueryRoutingService.QueryRoute route = queryRoutingService.classify(question);
        List<HybridRetrieverService.ScoredChunk> candidates = retrieveByRoute(route, question, kbIds);
        if (candidates.isEmpty()) {
            return RagResponse.notFound();
        }

        List<HybridRetrieverService.ScoredChunk> filtered = confidenceFilter.filter(candidates);
        if (filtered.isEmpty()) {
            return RagResponse.notFound();
        }

        List<HybridRetrieverService.ScoredChunk> trimmed = contextTrimmer.trim(filtered);
        String context = buildContext(trimmed);
        String answer = generateAnswer(question, context, trimmed.size(), route);
        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);

        if (System.currentTimeMillis() % 5 == 0) {
            var faithResult = hallucinationChecker.check(question, answer, context);
            if (!faithResult.isFaithful()) {
                log.warn("[FullRagPipeline] hallucination check failed score={} reason={}",
                        faithResult.score(), faithResult.reason());
            }
        }

        long elapsed = System.currentTimeMillis() - pipelineStart;
        log.info("[FullRagPipeline] route={} elapsed={}ms sources={} question={}",
                route, elapsed, sources.size(), preview(question));

        RagResponse response = RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs((int) elapsed)
                .build();
        queryCacheService.putToCache(cacheQuestion, kbIds, response);
        return response;
    }

    private List<HybridRetrieverService.ScoredChunk> retrieveByRoute(
            QueryRoutingService.QueryRoute route,
            String question,
            List<Long> kbIds) {
        return switch (route) {
            case SIMPLE -> hybridRetriever.retrieveVectorOnly(question, kbIds, simpleTopN);
            case STANDARD -> hybridRetriever.retrieve(question, kbIds, standardTopN);
            case COMPLEX -> {
                List<HybridRetrieverService.ScoredChunk> candidates =
                        enhancedRetriever.retrieveWithHyde(question, kbIds, complexCandidateTopN);
                yield rerankerService.rerank(question, candidates, rerankerTopN);
            }
        };
    }

    private String buildContext(List<HybridRetrieverService.ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            var sc = chunks.get(i);
            sb.append("[ref").append(i + 1).append("]");
            if (sc.chunk().getContentType() != null) {
                sb.append("[").append(sc.chunk().getContentType()).append("]");
            }
            if (sc.chunk().getPageNum() != null) {
                sb.append(" p.").append(sc.chunk().getPageNum());
            }
            if (sc.chunk().getSectionTitle() != null && !sc.chunk().getSectionTitle().isBlank()) {
                sb.append(" ").append(sc.chunk().getSectionTitle());
            }
            if (sc.chunk().getTableCaption() != null && !sc.chunk().getTableCaption().isBlank()) {
                sb.append("\nCaption: ").append(sc.chunk().getTableCaption());
            }
            sb.append("\n").append(sc.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private String generateAnswer(String question, String context, int chunkCount, QueryRoutingService.QueryRoute route) {
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(question, context, chunkCount, route);
        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
    }

    private String preview(String question) {
        if (question == null) {
            return "";
        }
        return question.substring(0, Math.min(40, question.length()));
    }
}