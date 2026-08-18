package com.yooooo.rag.service.retrieval;

import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.repository.DocChunkRepository;
import com.yooooo.rag.repository.KbPermissionRepository;
import com.yooooo.rag.repository.KnowledgeBaseRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.embedding.EmbeddingService;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 结合向量检索和全文检索，返回带分数的候选文本块。
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
    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;
    @Value("${rag.retrieval.fulltext-top-k:20}")
    private int fulltextTopK;
    private static final int RRF_K = 60;

    public List<ScoredChunk> retrieve(String question, List<Long> kbIds, int topN) {
        float[] queryEmbedding = embeddingService.embed(question);
        String embeddingStr = toVectorString(queryEmbedding);
        List<DocChunk> vectorResults = kbIds.stream()
                .flatMap(kbId -> chunkRepository.findByVectorSimilarity(kbId, embeddingStr, vectorTopK).stream())
                .collect(Collectors.toList());
        String tsQuery = tsQueryBuilder.build(question);
        List<DocChunk> fulltextResults = new ArrayList<>();
        if (tsQuery != null) {
            fulltextResults = kbIds.stream()
                    .flatMap(kbId -> chunkRepository.findByFullTextSearch(kbId, tsQuery, fulltextTopK).stream())
                    .collect(Collectors.toList());
        }
        log.debug("[HybridRetriever] 向量检索召回={}，全文检索召回={}",
                vectorResults.size(), fulltextResults.size());
        List<ScoredChunk> merged = rrfMerge(vectorResults, fulltextResults);
        List<ScoredChunk> topResults = merged.stream()
                .limit(topN)
                .collect(Collectors.toList());
        log.info("[HybridRetriever] RRF 融合后 TopN={}，返回 {} 条", topN, topResults.size());
        return topResults;
    }

    public List<ScoredChunk> retrieveVectorOnly(String question, List<Long> kbIds, int topN) {
        float[] queryEmbedding = embeddingService.embed(question);
        String embeddingStr = toVectorString(queryEmbedding);
        List<DocChunk> vectorResults = kbIds.stream()
                .flatMap(kbId -> chunkRepository.findByVectorSimilarity(kbId, embeddingStr, topN).stream())
                .limit(topN)
                .collect(Collectors.toList());

        List<ScoredChunk> results = new ArrayList<>();
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            results.add(new ScoredChunk(vectorResults.get(rank), 1.0 / (rank + 1)));
        }
        log.info("[HybridRetriever] Vector only TopN={} returned={}", topN, results.size());
        return results;
    }
    public List<ScoredChunk> retrieveWithPermissionCheck(
            String question, List<Long> requestedKbIds, int topN) {
        List<Long> allowedKbIds = filterAllowedKbIds(requestedKbIds);

        if (allowedKbIds.isEmpty()) {
            throw new RuntimeException("您对所请求的知识库没有访问权限");
        }

        if (allowedKbIds.size() < requestedKbIds.size()) {
            List<Long> denied = requestedKbIds.stream()
                    .filter(id -> !allowedKbIds.contains(id))
                    .toList();
            log.warn("[权限过滤] userId={} 无权访问 kbIds={}，已过滤",
                    UserContext.getUserId(), denied);
        }

        return retrieve(question, allowedKbIds, topN);
    }

    private List<Long> filterAllowedKbIds(List<Long> kbIds) {
        if (UserContext.isAdmin()) return kbIds;

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();

        return kbIds.stream()
                .filter(kbId -> {
                    boolean isPublic = kbRepository.findById(kbId)
                            .map(kb -> kb.getIsPublic())
                            .orElse(false);
                    if (isPublic) return true;

                    return permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                            kbId, "USER", userId)
                            || permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                            kbId, "DEPARTMENT", deptId);
                })
                .toList();
    }

    private List<ScoredChunk> rrfMerge(List<DocChunk> vectorList, List<DocChunk> fulltextList) {
        Map<Long, Double> scoreMap = new LinkedHashMap<>();

        Map<Long, DocChunk> chunkMap = new HashMap<>();

        for (int rank = 0; rank < vectorList.size(); rank++) {
            DocChunk chunk = vectorList.get(rank);

            double rrfScore = 1.0 / (RRF_K + rank + 1);

            scoreMap.merge(chunk.getId(), rrfScore, Double::sum);
            chunkMap.put(chunk.getId(), chunk);
        }

        for (int rank = 0; rank < fulltextList.size(); rank++) {
            DocChunk chunk = fulltextList.get(rank);
            double rrfScore = 1.0 / (RRF_K + rank + 1);

            scoreMap.merge(chunk.getId(), rrfScore, Double::sum);
            chunkMap.put(chunk.getId(), chunk);
        }

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> new ScoredChunk(chunkMap.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
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
 * 带检索分数的文档分块结果。
 */

    public record ScoredChunk(DocChunk chunk, double score) {
        public Long id() { return chunk.getId(); }

        public String content() { return chunk.getContent(); }
    }
}
