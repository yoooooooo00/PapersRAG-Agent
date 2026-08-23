package com.yooooo.rag.service.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.yooooo.rag.entity.Paper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkEmbeddingTextBuilderTest {
    private final ChunkEmbeddingTextBuilder builder = new ChunkEmbeddingTextBuilder();

    @Test
    void metadataChunkContainsOnlyRequestedFields() {
        Paper paper = new Paper();
        paper.setTitle("Example Paper");
        paper.setYear(2024);
        paper.setAuthors("Alice; Bob");
        paper.setAffiliations("Example University");
        paper.setAbstractText("This must remain body content.");
        paper.setVenue("NeurIPS");
        paper.setDoi("10.1000/example");
        paper.setKeywords("RAG");

        var indexed = builder.build(paper, "example.pdf", List.of());

        assertEquals(1, indexed.size());
        assertEquals("PAPER_METADATA", indexed.get(0).chunk().getContentType());
        assertEquals("Paper title: Example Paper\n"
                        + "Publication year: 2024\n"
                        + "Authors: Alice; Bob\n"
                        + "Affiliations: Example University",
                indexed.get(0).chunk().getContent());
        assertFalse(indexed.get(0).chunk().getContent().contains("Abstract"));
        assertFalse(indexed.get(0).chunk().getContent().contains("Venue"));
        assertFalse(indexed.get(0).chunk().getContent().contains("Source file"));
    }

    @Test
    void skipsDuplicateFrontMatterButKeepsAbstractBody() {
        Paper paper = new Paper();
        paper.setTitle("Example Paper");
        paper.setAuthors("Alice Smith");
        paper.setAffiliations("Example University");
        var frontMatter = com.yooooo.rag.service.splitter.ChunkResult.builder()
                .chunkIndex(0).content("Alice Smith\nExample University")
                .sectionType("FRONTMATTER").contentType("FRONTMATTER").build();
        var abstractBody = com.yooooo.rag.service.splitter.ChunkResult.builder()
                .chunkIndex(1).content("Abstract body text")
                .sectionType("ABSTRACT").contentType("TEXT").build();

        var indexed = builder.build(paper, "example.pdf", List.of(frontMatter, abstractBody));

        assertEquals(2, indexed.size());
        assertEquals("PAPER_METADATA", indexed.get(0).chunk().getContentType());
        assertEquals("Abstract body text", indexed.get(1).chunk().getContent());
    }
}