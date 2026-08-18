package com.yooooo.rag.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 根据答案中的引用和检索结果构建可展示的来源列表。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceBuilder {
    private final CitationParser citationParser;
    private final KbDocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public List<RagResponse.Source> buildSources(
            String answer,
            List<HybridRetrieverService.ScoredChunk> chunks) {
        Set<Integer> citedIndices = citationParser.extractCitedIndices(answer);

        if (citedIndices.isEmpty()) {
            log.debug("[SourceBuilder] 模型未标注引用，使用所有 chunk 作为来源");
            citedIndices = new LinkedHashSet<>();
            for (int i = 1; i <= chunks.size(); i++) citedIndices.add(i);
        }

        Set<Long> docIds = chunks.stream()
                .map(sc -> sc.chunk().getDocId())
                .collect(Collectors.toSet());
        Map<Long, KbDocument> docMap = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, d -> d));

        List<RagResponse.Source> sources = new ArrayList<>();
        for (int idx : citedIndices) {
            if (idx < 1 || idx > chunks.size()) continue;
            HybridRetrieverService.ScoredChunk sc = chunks.get(idx - 1);
            KbDocument doc = docMap.get(sc.chunk().getDocId());
            sources.add(RagResponse.Source.builder()
                    .chunkId(sc.id())
                    .docId(sc.chunk().getDocId())
                    .docName(doc != null ? doc.getFileName() : "未知文档")
                    .pageNum(sc.chunk().getPageNum())
                    .sectionTitle(sc.chunk().getSectionTitle())
                    .excerpt(sc.content().substring(0, Math.min(200, sc.content().length())))
                    .score(sc.score())
                    .build());
        }
        return sources;
    }

    public String sourcesToJson(List<RagResponse.Source> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            log.error("[SourceBuilder] 来源序列化失败：{}", e.getMessage());
            return "[]";
        }
    }
}
