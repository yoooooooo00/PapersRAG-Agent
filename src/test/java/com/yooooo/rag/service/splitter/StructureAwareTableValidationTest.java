package com.yooooo.rag.service.splitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yooooo.rag.service.loader.AcademicSectionDetector;
import com.yooooo.rag.service.loader.ParseResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureAwareTableValidationTest {
    private final StructureAwareChunkSplitter splitter =
            new StructureAwareChunkSplitter();
    private final ChunkConfig config = ChunkConfig.builder()
            .chunkSize(1200).chunkOverlap(150).structureAware(true).build();

    @Test
    void downgradesOneRowPseudoTableToText() {
        ParseResult parsed = document("[TABLE]\n| Mean | Reciprocal | Rank | explanation |\n"
                + "| --- | --- | --- | --- |\n"
                + "| MRR | measures | average | accuracy |\n[/TABLE]");

        var chunks = splitter.split(parsed, config);

        assertEquals(1, chunks.size());
        assertEquals("TEXT", chunks.get(0).getContentType());
    }

    @Test
    void keepsConsistentMultiRowTable() {
        ParseResult parsed = document("[TABLE_CAPTION]Table 1: Results.[/TABLE_CAPTION]\n[TABLE]\n"
                + "| Method | MRR |\n| --- | --- |\n| A | 0.5 |\n| B | 0.6 |\n[/TABLE]");

        var chunks = splitter.split(parsed, config);

        assertEquals(1, chunks.size());
        assertEquals("TABLE", chunks.get(0).getContentType());
        assertEquals("Table 1: Results.", chunks.get(0).getTableCaption());
        org.junit.jupiter.api.Assertions.assertTrue(chunks.get(0).getContent().contains("| Method | MRR |"));
        org.junit.jupiter.api.Assertions.assertTrue(chunks.get(0).getContent().contains("| A | 0.5 |"));
    }

    @Test
    void preservesSourcePageWhenOneSectionSpansPages() {
        ParseResult parsed = ParseResult.builder().success(true).totalPages(2)
                .pages(List.of(
                        ParseResult.PageContent.builder().pageNum(1).text("First page body.")
                                .sectionTitle("4 Method").sectionType("METHOD").contentType("TEXT").build(),
                        ParseResult.PageContent.builder().pageNum(2).text("Second page body.")
                                .sectionTitle("4 Method").sectionType("METHOD").contentType("TEXT").build()))
                .build();
        var chunks = splitter.split(parsed, config);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).getPageNum());
        assertEquals(2, chunks.get(1).getPageNum());
    }

    @Test
    void marksLeadingUnsectionedPageOneFragmentsAsFrontMatter() {
        ParseResult parsed = ParseResult.builder().success(true).totalPages(1)
                .pages(List.of(
                        ParseResult.PageContent.builder().pageNum(1).text("Title\nAlice\nUniversity")
                                .contentType("TEXT").build(),
                        ParseResult.PageContent.builder().pageNum(1).text("Abstract\nBody")
                                .sectionTitle("Abstract").sectionType("ABSTRACT").contentType("TEXT").build()))
                .build();
        var chunks = splitter.split(parsed, config);
        assertEquals(2, chunks.size());
        assertEquals("FRONTMATTER", chunks.get(0).getContentType());
        assertEquals("FRONTMATTER", chunks.get(0).getSectionType());
        assertTrue(chunks.get(1).getContent().contains("Abstract"));
    }
    private ParseResult document(String text) {
        return ParseResult.builder().success(true).totalPages(1)
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1).text(text).sectionTitle("5 Experiments")
                        .sectionType("EXPERIMENTS").contentType("MIXED").build()))
                .build();
    }
}