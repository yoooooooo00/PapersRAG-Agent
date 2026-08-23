package com.yooooo.rag.service.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yooooo.rag.entity.Paper;
import com.yooooo.rag.service.loader.ParseResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaperMetadataExtractorTest {
    private final PaperMetadataExtractor extractor = new PaperMetadataExtractor();

    @Test
    void extractsAffiliationsOnlyFromFrontMatter() {
        Paper paper = new Paper();
        paper.setTitle("A Long Example Paper Title");
        ParseResult parsed = ParseResult.builder()
                .success(true)
                .title("A Long Example Paper Title")
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text("A Long Example\nPaper Title\nAlice Smith, Bob Lee\n"
                                + "Department of Computer Science, Example University\n"
                                + "{alice, bob}@example.edu\nAbstract\n"
                                + "The introduction mentions Another University.\n"
                                + "1 Introduction\nBody text")
                        .build()))
                .build();

        extractor.fillMissingMetadata(paper, parsed, "example.pdf");

        assertEquals("Alice Smith, Bob Lee", paper.getAuthors());
        assertEquals("Department of Computer Science, Example University", paper.getAffiliations());
    }

    @Test
    void prefersStructuredMetadataOverHeaderHeuristics() {
        Paper paper = new Paper();
        paper.setTitle("uploaded-file");
        ParseResult parsed = ParseResult.builder()
                .success(true)
                .title("Fallback Title")
                .paperMetadata(ParseResult.PaperMetadata.builder()
                        .title("Structured Title")
                        .authors("Jane Doe; John Smith")
                        .affiliations("Example University")
                        .publicationYear(2024)
                        .build())
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1).text("Abstract\nBody\n1 Introduction\nText").build()))
                .build();

        extractor.fillMissingMetadata(paper, parsed, "uploaded-file.pdf");

        assertEquals("Structured Title", paper.getTitle());
        assertEquals("Jane Doe; John Smith", paper.getAuthors());
        assertEquals("Example University", paper.getAffiliations());
        assertEquals(2024, paper.getYear());
    }
}