package com.yooooo.rag.service.rag;

import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.service.retrieval.ContextTrimmerService;
import com.yooooo.rag.service.retrieval.EnhancedRetrieverService;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import com.yooooo.rag.service.retrieval.QueryCacheService;
import com.yooooo.rag.service.retrieval.QueryNormalizerService;
import com.yooooo.rag.service.retrieval.QueryRoutingService;
import com.yooooo.rag.service.permission.PermissionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FullRagPipeline {
    private final HybridRetrieverService hybridRetriever;
    private final EnhancedRetrieverService enhancedRetriever;
    private final ContextTrimmerService contextTrimmer;
    private final RagContextBuilder ragContextBuilder;
    private final SourceBuilder sourceBuilder;
    private final HallucinationChecker hallucinationChecker;
    private final QueryRoutingService queryRoutingService;
    private final QueryNormalizerService queryNormalizerService;
    private final QueryCacheService queryCacheService;
    private final ChatClient chatClient;
    private final PermissionService permissionService;

    @Value("${rag.routing.simple-top-n:5}")
    private int simpleTopN;
    @Value("${rag.routing.standard-top-n:10}")
    private int standardTopN;
    @Value("${rag.routing.complex-candidate-top-n:20}")
    private int complexCandidateTopN;
    @Value("${rag.routing.simple-as-standard-experiment:false}")
    private boolean simpleAsStandardExperiment;

    public RagResponse query(String question, List<Long> kbIds) {
        long start = System.currentTimeMillis();
        String normalized = queryNormalizerService.normalize(question);
        RagResponse cached = queryCacheService.getFromCache(normalized, kbIds);
        if (cached != null) {
            cached.setLatencyMs((int) (System.currentTimeMillis() - start));
            return cached;
        }
        QueryPlan plan = prepare(question, kbIds);
        if (plan.candidates().isEmpty() || plan.trimmed().isEmpty()) return RagResponse.notFound(plan.route());
        String answer = generateAnswer(question, plan.context(), plan.trimmed().size(), plan.route());
        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, plan.trimmed());
        if (System.currentTimeMillis() % 5 == 0) {
            var faithResult = hallucinationChecker.check(question, answer, plan.context());
            if (!faithResult.isFaithful()) log.warn("[FullRagPipeline] hallucination check failed score={} reason={}", faithResult.score(), faithResult.reason());
        }
        int latency = (int) (System.currentTimeMillis() - start);
        RagResponse response = RagResponse.builder().answer(answer).sources(sources).latencyMs(latency)
                .queryRoute(plan.route()).retrievedChunkIds(ids(plan.candidates())).trimmedChunkIds(ids(plan.trimmed())).build();
        queryCacheService.putToCache(normalized, kbIds, response);
        return response;
    }

    public RagResponse getCached(String question, List<Long> kbIds) {
        return queryCacheService.getFromCache(queryNormalizerService.normalize(question), kbIds);
    }

    public void cache(String question, List<Long> kbIds, RagResponse response) {
        queryCacheService.putToCache(queryNormalizerService.normalize(question), kbIds, response);
    }

    public QueryPlan prepare(String question, List<Long> kbIds) {
        QueryRoutingService.QueryRoute route = queryRoutingService.classify(question);
        List<HybridRetrieverService.ScoredChunk> candidates = retrieveByRoute(route, question, kbIds);
        if (candidates.isEmpty()) return new QueryPlan(route, candidates, List.of(), "");
        List<HybridRetrieverService.ScoredChunk> trimmed = contextTrimmer.trim(candidates);
        return new QueryPlan(route, candidates, trimmed, ragContextBuilder.buildContext(trimmed));
    }

    private List<HybridRetrieverService.ScoredChunk> retrieveByRoute(QueryRoutingService.QueryRoute route, String question, List<Long> kbIds) {
        return switch (route) {
            case SIMPLE -> simpleAsStandardExperiment ? hybridRetriever.retrieve(question, kbIds, standardTopN) : hybridRetriever.retrieveVectorOnly(question, kbIds, simpleTopN);
            case STANDARD -> hybridRetriever.retrieve(question, kbIds, standardTopN);
            case COMPLEX -> enhancedRetriever.retrieveWithTrace(question, kbIds, complexCandidateTopN).getChunks();
        };
    }

    private String generateAnswer(String question, String context, int chunkCount, QueryRoutingService.QueryRoute route) {
        String prompt = RagPromptTemplate.buildSystemPrompt(question, context, chunkCount, route);
        return chatClient.prompt().system(prompt).user(question).call().content();
    }

    private Long[] ids(List<HybridRetrieverService.ScoredChunk> chunks) {
        return chunks.stream().map(HybridRetrieverService.ScoredChunk::id).distinct().toArray(Long[]::new);
    }

    public record QueryPlan(QueryRoutingService.QueryRoute route, List<HybridRetrieverService.ScoredChunk> candidates, List<HybridRetrieverService.ScoredChunk> trimmed, String context) {}
}
