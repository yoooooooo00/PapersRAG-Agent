package com.yooooo.rag.service.retrieval;

import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.repository.DocChunkRepository;
import com.yooooo.rag.repository.KbPermissionRepository;
import com.yooooo.rag.repository.KnowledgeBaseRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.embedding.EmbeddingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Combines vector search and full-text search, returning scored chunks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetrieverService {
    private final EmbeddingService embeddingService;
    private final DocChunkRepository chunkRepository;
    private final TsQueryBuilder tsQueryBuilder;
    private final KnowledgeBaseRepository kbRepository;
    private final KbPermissionRepository permissionRepository;
    private final ConfidenceFilter confidenceFilter;

    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;
    @Value("${rag.retrieval.fulltext-top-k:20}")
    private int fulltextTopK;
    private static final int RRF_K = 60;

    public List<ScoredChunk> retrieve(String question, List<Long> kbIds, int topN) {
        return retrieveWithTrace(question, kbIds, topN).getChunks();
    }

    public RetrievalOutcome retrieveWithTrace(String question, List<Long> kbIds, int topN) {
        float[] queryEmbedding = embeddingService.embed(question);
        String embeddingStr = toVectorString(queryEmbedding);

        List<ScoredChunk> vectorResults = kbIds.stream()
                .flatMap(kbId -> hydrateScoredChunks(
                        chunkRepository.findVectorSimilarityScores(kbId, embeddingStr, vectorTopK)).stream())
                .collect(Collectors.toList());

        String tsQuery = tsQueryBuilder.build(question);
        List<ScoredChunk> fulltextResults = new ArrayList<>();
        if (tsQuery != null) {
            fulltextResults = kbIds.stream()
                    .flatMap(kbId -> hydrateScoredChunks(
                            chunkRepository.findFullTextSearchScores(kbId, tsQuery, fulltextTopK)).stream())
                    .collect(Collectors.toList());
        }

        List<ScoredChunk> vectorFiltered = confidenceFilter.filterVector(vectorResults);
        List<ScoredChunk> fulltextFiltered = confidenceFilter.filterFulltext(fulltextResults);

        log.debug("[HybridRetriever] vectorResults={} fulltextResults={}",
                vectorFiltered.size(), fulltextFiltered.size());

        List<ScoredChunk> merged = rrfMerge(vectorFiltered, fulltextFiltered);
        List<ScoredChunk> topResults = merged.stream()
                .limit(topN)
                .collect(Collectors.toList());
        log.info("[HybridRetriever] RRF merged TopN={} returned={}", topN, topResults.size());

        RetrievalTrace trace = new RetrievalTrace();
        trace.setRoute("STANDARD");
        trace.setQuestion(question);
        trace.setTopN(topN);
        trace.setVectorTopK(vectorTopK);
        trace.setFulltextTopK(fulltextTopK);
        trace.setMinScore(confidenceFilter.getMinScore());
        trace.getStages().add(stageTrace("vector_raw", vectorResults));
        trace.getStages().add(stageTrace("vector_filtered", vectorFiltered));
        trace.getStages().add(stageTrace("fulltext_raw", fulltextResults));
        trace.getStages().add(stageTrace("fulltext_filtered", fulltextFiltered));
        trace.getStages().add(stageTrace("rrf_merged", merged));
        trace.getStages().add(stageTrace("final", topResults));

        return new RetrievalOutcome(topResults, trace);
    }

    public List<ScoredChunk> retrieveVectorOnly(String question, List<Long> kbIds, int topN) {
        return retrieveVectorOnlyWithTrace(question, kbIds, topN).getChunks();
    }

    public RetrievalOutcome retrieveVectorOnlyWithTrace(String question, List<Long> kbIds, int topN) {
        float[] queryEmbedding = embeddingService.embed(question);
        String embeddingStr = toVectorString(queryEmbedding);
        List<ScoredChunk> vectorResults = kbIds.stream()
                .flatMap(kbId -> hydrateScoredChunks(
                        chunkRepository.findVectorSimilarityScores(kbId, embeddingStr, topN)).stream())
                .collect(Collectors.toList());

        List<ScoredChunk> filtered = confidenceFilter.filterVector(vectorResults).stream()
                .limit(topN)
                .collect(Collectors.toList());
        log.info("[HybridRetriever] Vector only TopN={} returned={}", topN, filtered.size());

        RetrievalTrace trace = new RetrievalTrace();
        trace.setRoute("SIMPLE");
        trace.setQuestion(question);
        trace.setTopN(topN);
        trace.setVectorTopK(vectorTopK);
        trace.setFulltextTopK(fulltextTopK);
        trace.setMinScore(confidenceFilter.getMinScore());
        trace.getStages().add(stageTrace("vector_raw", vectorResults));
        trace.getStages().add(stageTrace("vector_filtered", filtered));
        trace.getStages().add(stageTrace("final", filtered));

        return new RetrievalOutcome(filtered, trace);
    }

    public List<ScoredChunk> retrieveWithPermissionCheck(String question, List<Long> requestedKbIds, int topN) {
        List<Long> allowedKbIds = filterAllowedKbIds(requestedKbIds);

        if (allowedKbIds.isEmpty()) {
            throw new RuntimeException("鎮ㄥ鎵€璇锋眰鐨勭煡璇嗗簱娌℃湁璁块棶鏉冮檺");
        }

        if (allowedKbIds.size() < requestedKbIds.size()) {
            List<Long> denied = requestedKbIds.stream()
                    .filter(id -> !allowedKbIds.contains(id))
                    .toList();
            log.warn("[鏉冮檺杩囨护] userId={} 鏃犳潈璁块棶 kbIds={}锛屽凡杩囨护",
                    UserContext.getUserId(), denied);
        }

        return retrieve(question, allowedKbIds, topN);
    }

    private List<Long> filterAllowedKbIds(List<Long> kbIds) {
        if (UserContext.isAdmin()) {
            return kbIds;
        }

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();

        return kbIds.stream()
                .filter(kbId -> {
                    boolean isPublic = kbRepository.findById(kbId)
                            .map(kb -> kb.getIsPublic())
                            .orElse(false);
                    if (isPublic) {
                        return true;
                    }

                    return permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                            kbId, "USER", userId)
                            || permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                            kbId, "DEPARTMENT", deptId);
                })
                .toList();
    }

    private List<ScoredChunk> rrfMerge(List<ScoredChunk> vectorList, List<ScoredChunk> fulltextList) {
        Map<Long, Double> scoreMap = new LinkedHashMap<>();
        Map<Long, DocChunk> chunkMap = new HashMap<>();

        for (int rank = 0; rank < vectorList.size(); rank++) {
            ScoredChunk chunk = vectorList.get(rank);
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            scoreMap.merge(chunk.id(), rrfScore, Double::sum);
            chunkMap.put(chunk.id(), chunk.chunk());
        }

        for (int rank = 0; rank < fulltextList.size(); rank++) {
            ScoredChunk chunk = fulltextList.get(rank);
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            scoreMap.merge(chunk.id(), rrfScore, Double::sum);
            chunkMap.put(chunk.id(), chunk.chunk());
        }

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> new ScoredChunk(chunkMap.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    private List<ScoredChunk> hydrateScoredChunks(List<DocChunkRepository.ChunkScoreView> scoredViews) {
        if (scoredViews == null || scoredViews.isEmpty()) {
            return List.of();
        }

        List<Long> ids = scoredViews.stream()
                .map(DocChunkRepository.ChunkScoreView::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, DocChunk> chunkMap = chunkRepository.findByIds(ids).stream()
                .collect(Collectors.toMap(DocChunk::getId, c -> c));

        List<ScoredChunk> results = new ArrayList<>();
        for (DocChunkRepository.ChunkScoreView view : scoredViews) {
            if (view.getId() == null) {
                continue;
            }
            DocChunk chunk = chunkMap.get(view.getId());
            if (chunk != null) {
                results.add(new ScoredChunk(chunk, view.getScore() == null ? 0.0 : view.getScore()));
            }
        }
        return results;
    }

    private StageTrace stageTrace(String name, List<ScoredChunk> chunks) {
        StageTrace stage = new StageTrace();
        stage.setName(name);
        stage.setChunks(chunks.stream()
                .map(sc -> new ChunkScoreTrace(sc.id(), sc.score()))
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

    /**
     * Scored document chunk result.
     */
    public record ScoredChunk(DocChunk chunk, double score) {
        public Long id() { return chunk.getId(); }

        public String content() { return chunk.getContent(); }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalOutcome {
        private List<ScoredChunk> chunks;
        private RetrievalTrace trace;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalTrace {
        private String route;
        private String question;
        private String hydeQuestion;
        private Integer topN;
        private Integer vectorTopK;
        private Integer fulltextTopK;
        private Double minScore;
        private List<StageTrace> stages = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageTrace {
        private String name;
        private List<ChunkScoreTrace> chunks = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkScoreTrace {
        private Long id;
        private Double score;
    }
}
