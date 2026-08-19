package com.yooooo.rag.service.indexing;

import com.yooooo.rag.entity.Paper;
import com.yooooo.rag.service.splitter.ChunkResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ChunkEmbeddingTextBuilder {
    public List<IndexableChunk> build(Paper paper, String fileName, List<ChunkResult> chunks) {
        List<IndexableChunk> result = new ArrayList<>();
        int syntheticIndex = chunks == null ? 0 : chunks.size();

        String paperMetadata = buildPaperMetadataEmbeddingText(paper, fileName);
        if (!isBlank(paperMetadata)) {
            ChunkResult metadataChunk = ChunkResult.builder()
                    .chunkIndex(syntheticIndex++)
                    .content(paperMetadata)
                    .sectionType("FRONTMATTER")
                    .contentType("PAPER_METADATA")
                    .estimatedTokens(estimateTokens(paperMetadata))
                    .build();
            result.add(new IndexableChunk(metadataChunk, paperMetadata));
        }

        if (chunks == null || chunks.isEmpty()) {
            return result;
        }

        for (ChunkResult chunk : chunks) {
            if (shouldSkip(chunk)) {
                continue;
            }
            String embeddingText = buildChunkEmbeddingText(paper, fileName, chunk);
            if (!isBlank(embeddingText)) {
                result.add(new IndexableChunk(chunk, embeddingText));
            }
        }
        return result;
    }

    private boolean shouldSkip(ChunkResult chunk) {
        if (chunk == null) {
            return true;
        }
        return "REFERENCES".equalsIgnoreCase(chunk.getSectionType())
                && !"TABLE".equalsIgnoreCase(normalizeContentType(chunk.getContentType(), chunk.getContent()));
    }

    private String buildPaperMetadataEmbeddingText(Paper paper, String fileName) {
        if (paper == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        addField(parts, "Paper title", paper.getTitle());
        addField(parts, "Authors", paper.getAuthors());
        addField(parts, "Year", paper.getYear() == null ? null : String.valueOf(paper.getYear()));
        addField(parts, "Venue", paper.getVenue());
        addField(parts, "DOI", paper.getDoi());
        addField(parts, "arXiv", paper.getArxivId());
        addField(parts, "Keywords", paper.getKeywords());
        addField(parts, "Abstract", paper.getAbstractText());
        addField(parts, "Source file", fileName);
        return String.join("\n", parts).strip();
    }

    private String buildChunkEmbeddingText(Paper paper, String fileName, ChunkResult chunk) {
        List<String> parts = new ArrayList<>();
        if (paper != null) {
            addField(parts, "Paper title", paper.getTitle());
            addField(parts, "Authors", paper.getAuthors());
            addField(parts, "Year", paper.getYear() == null ? null : String.valueOf(paper.getYear()));
        }
        addField(parts, "Source file", fileName);
        addField(parts, "Section", chunk.getSectionTitle());
        addField(parts, "Section type", chunk.getSectionType());
        addField(parts, "Page", chunk.getPageNum() == null ? null : String.valueOf(chunk.getPageNum()));

        String contentType = normalizeContentType(chunk.getContentType(), chunk.getContent());
        addField(parts, "Content type", contentType);
        addField(parts, "Content", normalizeChunkContent(chunk, contentType));
        return String.join("\n", parts).strip();
    }

    private String normalizeChunkContent(ChunkResult chunk, String contentType) {
        String content = chunk.getContent();
        if (content == null) {
            return null;
        }
        if ("TABLE".equalsIgnoreCase(contentType)) {
            return content
                    .replace("[TABLE]", "")
                    .replace("[/TABLE]", "")
                    .replaceAll("\\n{3,}", "\n\n")
                    .strip();
        }
        return content.strip();
    }

    private String normalizeContentType(String contentType, String content) {
        if (!isBlank(contentType)) {
            return contentType.toUpperCase(Locale.ROOT);
        }
        if (content != null && content.contains("[TABLE]")) {
            return "TABLE";
        }
        return "TEXT";
    }

    private void addField(List<String> parts, String label, String value) {
        if (!isBlank(value)) {
            parts.add(label + ": " + value.strip());
        }
    }

    private int estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        int chinese = 0;
        int other = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chinese++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        return (int) (chinese * 1.5 + other * 0.3);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record IndexableChunk(ChunkResult chunk, String embeddingText) {}
}
