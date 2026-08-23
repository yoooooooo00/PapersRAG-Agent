package com.yooooo.rag.service.indexing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yooooo.rag.service.loader.AcademicSectionDetector;
import com.yooooo.rag.service.loader.ParseResult;
import com.yooooo.rag.service.splitter.ChunkResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IndexQualityValidatorTest {
    private IndexQualityValidator validator;
    private ParseResult parsed;

    @BeforeEach
    void setUp() {
        validator = new IndexQualityValidator(new AcademicSectionDetector());
        ReflectionTestUtils.setField(validator, "strict", true);
        ReflectionTestUtils.setField(validator, "configuredChunkSize", 1200);
        parsed = ParseResult.builder().success(true).totalPages(1)
                .pages(List.of(ParseResult.PageContent.builder().pageNum(1).text("body").build()))
                .build();
    }

    @Test
    void rejectsBodyTextInsidePaperMetadata() {
        ChunkResult chunk = ChunkResult.builder().chunkIndex(0)
                .contentType("PAPER_METADATA")
                .content("Paper title: X\nAbstract: must not be here").build();

        var report = validator.validate(parsed,
                List.of(new ChunkEmbeddingTextBuilder.IndexableChunk(chunk, chunk.getContent())));

        assertFalse(report.passed());
    }

    @Test
    void acceptsCleanMetadataAndBody() {
        ChunkResult metadata = ChunkResult.builder().chunkIndex(0)
                .contentType("PAPER_METADATA").content("Paper title: X\nAuthors: A").build();
        ChunkResult body = ChunkResult.builder().chunkIndex(1)
                .contentType("TEXT").sectionType("INTRODUCTION")
                .sectionTitle("1 Introduction").content("Body text").build();

        var report = validator.validate(parsed, List.of(
                new ChunkEmbeddingTextBuilder.IndexableChunk(metadata, metadata.getContent()),
                new ChunkEmbeddingTextBuilder.IndexableChunk(body, body.getContent())));

        assertTrue(report.passed());
    }
}