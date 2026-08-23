package com.yooooo.rag.service.retrieval;

import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.repository.DocChunkRepository;
import com.yooooo.rag.service.embedding.EmbeddingService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Enhanced retrieval with HyDE query expansion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedRetrieverService {
    private final HybridRetrieverService hybridRetriever;
    private final QueryRewriterService queryRewriter;
    private final EmbeddingService embeddingService;
    private final DocChunkRepository chunkRepository;
    private final ConfidenceFilter confidenceFilter;
    private final RerankerService rerankerService;

    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;
    @Value("${rag.retrieval.fulltext-top-k:20}")
    private int fulltextTopK;
    @Value("${reranker.top-n:5}")
    private int rerankerTopN;
    private static final int RRF_K = 60;

    public List<HybridRetrieverService.ScoredChunk> retrieveWithHyde(String question, List<Long> kbIds, int topN) {
        return retrieveWithTrace(question, kbIds, topN).getChunks();
    }

    public HybridRetrieverService.RetrievalOutcome retrieveWithTrace(String question, List<Long> kbIds, int topN) {
        HybridRetrieverService.RetrievalOutcome originalOutcome =
                hybridRetriever.retrieveWithTrace(question, kbIds, vectorTopK);

        String hydeAnswer = queryRewriter.generateHypotheticalAnswer(question);
        float[] hydeEmbedding = embeddingService.embed(hydeAnswer);
        String hydeEmbeddingStr = toVectorString(hydeEmbedding);
        List<HybridRetrieverService.ScoredChunk> hydeResults = kbIds.stream()
                .flatMap(kbId -> hydrateScoredChunks(
                        chunkRepository.findVectorSimilarityScores(kbId, hydeEmbeddingStr, vectorTopK)).stream())
                .collect(Collectors.toList());
        List<HybridRetrieverService.ScoredChunk> hydeFiltered = confidenceFilter.filterVector(hydeResults);
        log.debug("[EnhancedRetriever] originalResults={} hydeResults={}",
                originalOutcome.getChunks().size(), hydeFiltered.size());

        Map<Long, Double> scoreMap = new LinkedHashMap<>();
        Map<Long, DocChunk> chunkMap = new HashMap<>();

        for (int rank = 0; rank < originalOutcome.getChunks().size(); rank++) {
            HybridRetrieverService.ScoredChunk sc = originalOutcome.getChunks().get(rank);
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            scoreMap.merge(sc.id(), rrfScore, Double::sum);
            chunkMap.put(sc.id(), sc.chunk());
        }

        for (int rank = 0; rank < hydeFiltered.size(); rank++) {
            HybridRetrieverService.ScoredChunk sc = hydeFiltered.get(rank);
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            scoreMap.merge(sc.id(), rrfScore, Double::sum);
            chunkMap.put(sc.id(), sc.chunk());
        }

        List<HybridRetrieverService.ScoredChunk> merged = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> new HybridRetrieverService.ScoredChunk(chunkMap.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
        List<HybridRetrieverService.ScoredChunk> mergedTop = merged.stream()
                .limit(topN)
                .collect(Collectors.toList());
        List<HybridRetrieverService.ScoredChunk> reranked = rerankerService.rerank(question, mergedTop, rerankerTopN);

        HybridRetrieverService.RetrievalTrace trace = new HybridRetrieverService.RetrievalTrace();
        trace.setRoute("COMPLEX");
        trace.setQuestion(question);
        trace.setHydeQuestion(hydeAnswer);
        trace.setTopN(topN);
        trace.setVectorTopK(vectorTopK);
        trace.setFulltextTopK(fulltextTopK);
        trace.setMinScore(confidenceFilter.getMinScore());
        trace.getStages().addAll(prefixStages("original_", originalOutcome.getTrace().getStages()));
        trace.getStages().add(stageTrace("hyde_raw", hydeResults));
        trace.getStages().add(stageTrace("hyde_filtered", hydeFiltered));
        trace.getStages().add(stageTrace("rrf_merged", merged));
        trace.getStages().add(stageTrace("pre_rerank", mergedTop));
        trace.getStages().add(stageTrace("reranked", reranked));

        return new HybridRetrieverService.RetrievalOutcome(reranked, trace);
    }

    private List<HybridRetrieverService.StageTrace> prefixStages(String prefix, List<HybridRetrieverService.StageTrace> stages) {
        return stages.stream()
                .map(stage -> {
                    HybridRetrieverService.StageTrace copy = new HybridRetrieverService.StageTrace();
                    copy.setName(prefix + stage.getName());
                    copy.setChunks(stage.getChunks());
                    return copy;
                })
                .collect(Collectors.toList());
    }

    private List<HybridRetrieverService.ScoredChunk> hydrateScoredChunks(List<DocChunkRepository.ChunkScoreView> scoredViews) {
        if (scoredViews == null || scoredViews.isEmpty()) {
            return List.of();
        }

        List<Long> ids = scoredViews.stream()
                .map(DocChunkRepository.ChunkScoreView::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, DocChunk> chunkMap = chunkRepository.findByIds(ids).stream()
                .collect(Collectors.toMap(DocChunk::getId, c -> c));

        List<HybridRetrieverService.ScoredChunk> results = new java.util.ArrayList<>();
        for (DocChunkRepository.ChunkScoreView view : scoredViews) {
            if (view.getId() == null) {
                continue;
            }
            DocChunk chunk = chunkMap.get(view.getId());
            if (chunk != null) {
                results.add(new HybridRetrieverService.ScoredChunk(chunk, view.getScore() == null ? 0.0 : view.getScore()));
            }
        }
        return results;
    }

    private HybridRetrieverService.StageTrace stageTrace(String name, List<HybridRetrieverService.ScoredChunk> chunks) {
        HybridRetrieverService.StageTrace stage = new HybridRetrieverService.StageTrace();
        stage.setName(name);
        stage.setChunks(chunks.stream()
                .map(sc -> {
                    HybridRetrieverService.ChunkScoreTrace trace = new HybridRetrieverService.ChunkScoreTrace();
                    trace.setId(sc.id());
                    trace.setScore(sc.score());
                    return trace;
                })
                .collect(Collectors.toList()));
        return stage;
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
