package com.yooooo.rag.service.retrieval;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Filter low-confidence chunks by score threshold.
 */
@Component
@Slf4j
public class ConfidenceFilter {
    @Value("${rag.retrieval.vector-min-score:0.35}")
    private double vectorMinScore;

    @Value("${rag.retrieval.fulltext-min-score:0.05}")
    private double fulltextMinScore;

    /** Retain several ranked candidates when every score is below the threshold. */
    @Value("${rag.retrieval.confidence-fallback-top-k:5}")
    private int fallbackTopK;

    public List<HybridRetrieverService.ScoredChunk> filter(List<HybridRetrieverService.ScoredChunk> chunks) {
        return filterByScore(chunks, vectorMinScore);
    }

    public List<HybridRetrieverService.ScoredChunk> filterVector(List<HybridRetrieverService.ScoredChunk> chunks) {
        return filterByScore(chunks, vectorMinScore);
    }

    public List<HybridRetrieverService.ScoredChunk> filterFulltext(List<HybridRetrieverService.ScoredChunk> chunks) {
        return filterByScore(chunks, fulltextMinScore);
    }

    private List<HybridRetrieverService.ScoredChunk> filterByScore(
            List<HybridRetrieverService.ScoredChunk> chunks, double threshold) {
        List<HybridRetrieverService.ScoredChunk> filtered = chunks.stream()
                .filter(c -> c.score() >= threshold)
                .collect(Collectors.toList());

        if (filtered.isEmpty() && !chunks.isEmpty() && fallbackTopK > 0) {
            int keepCount = Math.max(1, Math.min(fallbackTopK, chunks.size()));
            filtered = chunks.stream()
                    .sorted(Comparator.comparingDouble(HybridRetrieverService.ScoredChunk::score).reversed())
                    .limit(keepCount)
                    .collect(Collectors.toList());
            log.debug("[ConfidenceFilter] all chunks are below minScore={}, keep fallback count={} bestScore={}",
                    threshold, filtered.size(), filtered.get(0).score());
        }

        int filteredCount = chunks.size() - filtered.size();
        if (filteredCount > 0) {
            log.debug("[ConfidenceFilter] filtered {} low-confidence chunks", filteredCount);
        }

        return filtered;
    }

    public double getMinScore() {
        return vectorMinScore;
    }
}
