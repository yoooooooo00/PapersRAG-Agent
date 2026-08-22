package com.yooooo.rag.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds source items from cited chunks for paper QA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceBuilder {
    private static final int NORMAL_EXCERPT_LIMIT = 220;
    private static final int TABLE_EXCERPT_LIMIT = 500;

    private final CitationParser citationParser;
    private final KbDocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public List<RagResponse.Source> buildSources(String answer, List<HybridRetrieverService.ScoredChunk> chunks) {
        Set<Integer> citedIndices = citationParser.extractCitedIndices(answer);
        if (citedIndices.isEmpty()) {
            citedIndices = new LinkedHashSet<>();
            for (int i = 1; i <= chunks.size(); i++) {
                citedIndices.add(i);
            }
        }

        Set<Long> docIds = chunks.stream()
                .map(sc -> sc.chunk().getDocId())
                .collect(Collectors.toSet());
        Map<Long, KbDocument> docMap = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, d -> d));

        List<RagResponse.Source> sources = new ArrayList<>();
        for (int idx : citedIndices) {
            if (idx < 1 || idx > chunks.size()) {
                continue;
            }
            HybridRetrieverService.ScoredChunk sc = chunks.get(idx - 1);
            DocChunk chunk = sc.chunk();
            KbDocument doc = docMap.get(chunk.getDocId());
            String contentType = chunk.getContentType() == null ? "TEXT" : chunk.getContentType();
            sources.add(RagResponse.Source.builder()
                    .chunkId(sc.id())
                    .docId(chunk.getDocId())
                    .docName(doc != null ? doc.getFileName() : "unknown document")
                    .pageNum(chunk.getPageNum())
                    .sectionTitle(chunk.getSectionTitle())
                    .excerpt(buildExcerpt(chunk))
                    .score(sc.score())
                    .contentType(contentType)
                    .tableCaption(chunk.getTableCaption())
                    .build());
        }
        return sources;
    }

    public String sourcesToJson(List<RagResponse.Source> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            log.error("[SourceBuilder] failed to serialize sources: {}", e.getMessage());
            return "[]";
        }
    }

    private String buildExcerpt(DocChunk chunk) {
        String contentType = chunk.getContentType() == null ? "TEXT" : chunk.getContentType();
        String raw = firstNonBlank(chunk.getRawContent(), chunk.getContent());
        if (raw == null) {
            return "";
        }
        int limit = "TABLE".equalsIgnoreCase(contentType) ? TABLE_EXCERPT_LIMIT : NORMAL_EXCERPT_LIMIT;
        return trimExcerpt(raw, limit);
    }

    private String trimExcerpt(String value, int limit) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit).strip() + "...";
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        if (second != null && !second.isBlank()) {
            return second.strip();
        }
        return null;
    }
}