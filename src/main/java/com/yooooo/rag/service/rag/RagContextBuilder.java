package com.yooooo.rag.service.rag;

import com.yooooo.rag.entity.Paper;
import com.yooooo.rag.repository.PaperRepository;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the prompt context for paper QA with paper metadata headers.
 */
@Service
@RequiredArgsConstructor
public class RagContextBuilder {
    private final PaperRepository paperRepository;

    public String buildContext(List<HybridRetrieverService.ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        Set<Long> paperIds = chunks.stream()
                .map(sc -> sc.chunk().getPaperId())
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, Paper> paperMap = paperRepository.findAllById(paperIds).stream()
                .collect(Collectors.toMap(Paper::getId, p -> p));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            var sc = chunks.get(i);
            var chunk = sc.chunk();
            Paper paper = chunk.getPaperId() == null ? null : paperMap.get(chunk.getPaperId());

            sb.append("[ref").append(i + 1).append("]");
            if (chunk.getContentType() != null) {
                sb.append("[").append(chunk.getContentType()).append("]");
            }
            if (paper != null && paper.getTitle() != null && !paper.getTitle().isBlank()) {
                sb.append(" title: ").append(paper.getTitle().strip());
            }
            if (paper != null && paper.getAuthors() != null && !paper.getAuthors().isBlank()) {
                sb.append(" | authors: ").append(paper.getAuthors().strip());
            }
            if (paper != null && paper.getYear() != null) {
                sb.append(" | year: ").append(paper.getYear());
            }
            if (chunk.getPageNum() != null) {
                sb.append(" | p.").append(chunk.getPageNum());
            }
            if (chunk.getSectionTitle() != null && !chunk.getSectionTitle().isBlank()) {
                sb.append(" | ").append(chunk.getSectionTitle().strip());
            }
            if (chunk.getTableCaption() != null && !chunk.getTableCaption().isBlank()) {
                sb.append("\nCaption: ").append(chunk.getTableCaption().strip());
            }
            sb.append("\n").append(sc.content()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
