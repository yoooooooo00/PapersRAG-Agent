package com.yooooo.rag.service.indexing;

import com.yooooo.rag.entity.Paper;
import com.yooooo.rag.service.splitter.ChunkResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ChunkEmbeddingTextBuilder {
    private static final Pattern TABLE_CAPTION_PATTERN = Pattern.compile("(?s)\\[TABLE_CAPTION\\](.*?)\\[/TABLE_CAPTION\\]");
    public List<IndexableChunk> build(Paper paper, String fileName, List<ChunkResult> chunks) {
        List<IndexableChunk> result = new ArrayList<>();
        int syntheticIndex = chunks == null ? 0 : chunks.size();

        String paperMetadata = buildPaperMetadataEmbeddingText(paper);
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

        // References and other skipped sections can leave gaps in the splitter's
        // original indexes. Re-number the actual indexed chunks contiguously.
        for (int i = 0; i < result.size(); i++) {
            result.get(i).chunk().setChunkIndex(i);
        }
        return result;
    }

    private boolean shouldSkip(ChunkResult chunk) {
        if (chunk == null) {
            return true;
        }
        String contentType = normalizeContentType(chunk.getContentType(), chunk.getContent());
        if ("FRONTMATTER".equalsIgnoreCase(chunk.getSectionType())
                || "FRONTMATTER".equalsIgnoreCase(contentType)) {
            return true;
        }
        return "REFERENCES".equalsIgnoreCase(chunk.getSectionType())
                && !"TABLE".equalsIgnoreCase(contentType);
    }

    private String buildPaperMetadataEmbeddingText(Paper paper) {
        if (paper == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        addField(parts, "Paper title", paper.getTitle());
        addField(parts, "Publication year", paper.getYear() == null ? null : String.valueOf(paper.getYear()));
        addField(parts, "Authors", paper.getAuthors());
        addField(parts, "Affiliations", paper.getAffiliations());
        return String.join("\n", parts).strip();
    }

    private String buildChunkEmbeddingText(Paper paper, String fileName, ChunkResult chunk) {
        List<String> parts = new ArrayList<>();
        // Paper identity is indexed by the dedicated metadata chunk. Repeating it in every
        // chunk dilutes the section/content signal for short chunks and tables.
        addField(parts, "Section", chunk.getSectionTitle());
        addField(parts, "Section type", chunk.getSectionType());
        addField(parts, "Page", chunk.getPageNum() == null ? null : String.valueOf(chunk.getPageNum()));
        addField(parts, "Table caption", resolveTableCaption(chunk));

        String contentType = normalizeContentType(chunk.getContentType(), chunk.getContent());
        addField(parts, "Content type", contentType);
        addField(parts, "Content", normalizeChunkContent(chunk, contentType));
        return String.join("\n", parts).strip();
    }

    private String resolveTableCaption(ChunkResult chunk) {
        if (!isBlank(chunk.getTableCaption())) {
            return chunk.getTableCaption();
        }
        if (chunk.getContent() == null) {
            return null;
        }
        Matcher matcher = TABLE_CAPTION_PATTERN.matcher(chunk.getContent());
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private String normalizeChunkContent(ChunkResult chunk, String contentType) {
        String content = chunk.getContent();
        if (content == null) {
            return null;
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
