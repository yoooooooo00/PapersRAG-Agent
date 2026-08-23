package com.yooooo.rag.service.indexing;

import com.yooooo.rag.service.indexing.ChunkEmbeddingTextBuilder.IndexableChunk;
import com.yooooo.rag.service.loader.AcademicSectionDetector;
import com.yooooo.rag.service.loader.ParseResult;
import com.yooooo.rag.service.splitter.ChunkResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Rejects structurally unsafe chunks before embeddings and database writes. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IndexQualityValidator {
    private final AcademicSectionDetector sectionDetector;

    @Value("${rag.index-quality.strict:true}")
    private boolean strict;

    @Value("${rag.chunk.size:1200}")
    private int configuredChunkSize;

    public QualityReport validate(ParseResult parsed, List<IndexableChunk> chunks) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (parsed == null || !parsed.isSuccess() || parsed.getPages() == null || parsed.getPages().isEmpty()) {
            errors.add("Parsed document has no pages");
            return new QualityReport(errors, warnings);
        }
        if (chunks == null || chunks.isEmpty()) {
            errors.add("No indexable chunks were produced");
            return new QualityReport(errors, warnings);
        }

        Set<Integer> indexes = new HashSet<>();
        int metadataChunks = 0;
        for (int position = 0; position < chunks.size(); position++) {
            ChunkResult chunk = chunks.get(position).chunk();
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                errors.add("Blank chunk at position " + position);
                continue;
            }
            if (!indexes.add(chunk.getChunkIndex())) {
                errors.add("Duplicate chunk_index " + chunk.getChunkIndex());
            }
            if (chunk.getChunkIndex() != position) {
                errors.add("Non-contiguous chunk_index at position " + position + ": " + chunk.getChunkIndex());
            }

            String contentType = normalize(chunk.getContentType());
            if ("PAPER_METADATA".equals(contentType)) {
                metadataChunks++;
                String lower = chunk.getContent().toLowerCase(Locale.ROOT);
                if (lower.contains("abstract:") || lower.contains("introduction")
                        || lower.contains("source file:") || lower.contains("keywords:")) {
                    errors.add("Paper metadata contains body or non-approved fields");
                }
            }
            if ("FRONTMATTER".equals(contentType)) {
                errors.add("Duplicate front matter reached the index");
            }
            if ("TEXT".equals(contentType) && chunk.getContent().length() > configuredChunkSize * 1.25) {
                errors.add("Text chunk " + chunk.getChunkIndex() + " exceeds configured size tolerance");
            }
            if ("TABLE".equals(contentType)) {
                if (chunk.getRawContent() == null || countDataRows(chunk.getRawContent()) < 2) {
                    errors.add("Table chunk " + chunk.getChunkIndex() + " has fewer than two data rows");
                }
                if (chunk.getTableCaption() == null || chunk.getTableCaption().isBlank()) {
                    warnings.add("Table chunk " + chunk.getChunkIndex() + " has no caption");
                }
            }

            String inferred = sectionDetector.inferSectionType(chunk.getSectionTitle(), chunk.getContent());
            if (inferred != null && chunk.getSectionType() != null
                    && !inferred.equalsIgnoreCase(chunk.getSectionType())) {
                warnings.add("Chunk " + chunk.getChunkIndex() + " section label " + chunk.getSectionType()
                        + " conflicts with heading-derived label " + inferred);
            }
        }
        if (metadataChunks > 1) errors.add("More than one paper metadata chunk was produced");
        return new QualityReport(List.copyOf(errors), List.copyOf(warnings));
    }

    public void validateOrThrow(ParseResult parsed, List<IndexableChunk> chunks) {
        QualityReport report = validate(parsed, chunks);
        report.warnings().forEach(warning -> log.warn("[IndexQuality] {}", warning));
        if (!report.errors().isEmpty()) {
            String message = String.join("; ", report.errors());
            if (strict) throw new IllegalStateException("Index quality validation failed: " + message);
            log.error("[IndexQuality] non-strict validation errors: {}", message);
        }
    }

    private int countDataRows(String rawTable) {
        int rows = 0;
        for (String line : rawTable.split("\\R")) {
            String value = line.strip();
            if (value.startsWith("|") && value.endsWith("|")
                    && !value.matches("^\\|(?:\\s*:?-{3,}:?\\s*\\|)+$")) {
                rows++;
            }
        }
        return Math.max(0, rows - 1);
    }

    private String normalize(String value) {
        return value == null ? "TEXT" : value.strip().toUpperCase(Locale.ROOT);
    }

    public record QualityReport(List<String> errors, List<String> warnings) {
        public boolean passed() { return errors.isEmpty(); }
    }
}