package com.yooooo.rag.service.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import com.yooooo.rag.service.splitter.ChunkConfig;
import com.yooooo.rag.service.splitter.StructureAwareChunkSplitter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PdfParserRealDocumentTest {
    @Test
    void extractsAllTablesFromProvidedAcademicPdf() throws Exception {
        String configuredPath = System.getProperty("rag.test.pdf");
        assumeTrue(configuredPath != null && !configuredPath.isBlank(), "Set -Drag.test.pdf to run");
        Path pdf = Path.of(configuredPath);
        assumeTrue(Files.isRegularFile(pdf), "Configured PDF does not exist");

        PdfParser parser = new PdfParser(new AcademicSectionDetector());
        ReflectionTestUtils.setField(parser, "cropHeaderPoints", 60f);
        ReflectionTestUtils.setField(parser, "cropFooterPoints", 50f);

        ParseResult result;
        try (InputStream input = Files.newInputStream(pdf)) {
            result = parser.parse(input, pdf.getFileName().toString());
        }

        assertTrue(result.isSuccess(), result.getErrorMsg());
        String parsed = result.getPages().stream()
                .map(ParseResult.PageContent::getText)
                .reduce("", (left, right) -> left + "\n" + right);


        assertTrue(parsed.contains("[TABLE_CAPTION]Table 1:"));
        assertTrue(parsed.contains("[TABLE_CAPTION]Table 2:"), contextAround(parsed, "Table 2"));
        assertTrue(parsed.contains("[TABLE_CAPTION]Table 3:"));
        assertTrue(parsed.contains("[TABLE_CAPTION]Table 4:"));
        assertTrue(parsed.contains("[TABLE_CAPTION]Table 5:"));
        assertEquals(5, count(parsed, "[TABLE]"));
        assertEquals(8, count(parsed, "[FIGURE_CAPTION]"), contextAround(parsed, "Figure 4"));
        assertTrue(parsed.contains("[FIGURE_CAPTION]Figure 4:"), contextAround(parsed, "Figure 4"));
        assertTrue(parsed.contains("[FIGURE_CAPTION]Figure 5:"), contextAround(parsed, "Figure 5"));
        assertFalse(parsed.matches("(?s).*[A-Za-z]-\\n[a-z].*"), "Line-end hyphenation remains");
        assertTrue(result.getPages().stream().anyMatch(page -> page.getPageNum() == 6
                && "4.3 Dynamic Adaptation".equals(page.getSectionTitle())));
        assertTrue(result.getPages().stream().anyMatch(page -> page.getPageNum() == 7
                && "4.4 Candidate Reasoning".equals(page.getSectionTitle())));
        assertTrue(result.getPages().stream().anyMatch(page -> page.getPageNum() == 8
                && "5.2 Performance Comparison".equals(page.getSectionTitle())));
        assertTrue(result.getPages().stream().anyMatch(page -> page.getPageNum() == 9
                && "5.3 Analysis of Dynamic Adaptation".equals(page.getSectionTitle())));
        assertTrue(result.getPages().stream().anyMatch(page -> page.getPageNum() == 15
                && "A.2 Prompt for Dynamic Adaptation".equals(page.getSectionTitle())));
        assertFalse(result.getPages().stream().anyMatch(page -> page.getSectionTitle() != null
                && (page.getSectionTitle().contains("://")
                || page.getSectionTitle().startsWith("A practical time decay")
                || page.getSectionTitle().equals("1 2 jk"))));
        assertTrue(result.getPages().stream().anyMatch(page -> page.getPageNum() == 19
                && "C.7 Parameter Analysis".equals(page.getSectionTitle())));
        String table1 = tableBlock(parsed, "Table 1:");
        assertTrue(table1.contains("Type"));
        assertTrue(table1.contains("Models"));
        assertTrue(table1.contains("GPT-NeoX"));
        assertTrue(table1.contains("Mixtral-8x7B-CoH"));
        assertTrue(table1.contains("0.471"));
        assertTrue(table1.contains("0.728"));
        assertFalse(table1.contains("| means the | result |"));
        assertTrue(table1.contains("| Type | Models | Train | ICEWS14 | | | | ICEWS05-15 | | | |"), table1);
        assertTrue(table1.contains("| | RE-NET | ✓ | 0.388 | 0.290 | 0.436 | 0.576 | 0.441 | 0.332 | 0.512 | 0.650 |"), table1);
        assertTrue(table1.contains("| ♠ | RE-GCN | ✓ | 0.425 | 0.320 | 0.476 | 0.627 | 0.478 | 0.371 | 0.535 | 0.682 |"), table1);
        assertTrue(table1.contains("| | GPT-NeoX | ✗ |"), table1);

        String table2 = tableBlock(parsed, "Table 2:");
        assertTrue(table2.contains("| Models | ICEWS14 | | | | ICEWS05-15 | | | |"), table2);
        assertTrue(table2.contains("| LLM-DA (TiRGN) w H | 0.450 | 0.345 | 0.502 | 0.656 | 0.503 | 0.395 | 0.563 | 0.709 |"), table2);

        String table3 = tableBlock(parsed, "Table 3:");
        assertTrue(table3.contains(
                "| Datasets | #Entities | #Relations | #Historical Data | #Current Data | #Future Data | #Granularity |"),
                table3);
        assertTrue(table3.contains("| ICEWS14 | 6,869 | 230 | 74,845 | 8,514 | 7,371 | 24 hours |"));
        assertTrue(table3.contains("| ICEWS05-15 | 10,094 | 251 | 368,868 | 46,302 | 46,159 | 24 hours |"));
        assertTrue(table3.contains("| ICEWS18 | 23,033 | 256 | 373,018 | 45,995 | 49,545 | 24 hours |"));

        String table4 = tableBlock(parsed, "Table 4:");
        assertTrue(table4.contains("| Type | Models | Train | ICEWS18 | | | |"), table4);
        assertTrue(table4.contains("| | RE-NET† [44] | ✓ | 0.287 | 0.188 | 0.327 | 0.482 |"), table4);
        assertTrue(table4.contains("| | GPT-NeoX [49] | ✗ |"), table4);

        String table5 = tableBlock(parsed, "Table 5:");
        assertTrue(table5.contains("Candidates for Dynamic Adaptation"));
        assertTrue(table5.contains("South_Africa"), table5);
        assertFalse(table5.contains("C.7 Parameter Analysis"));
        assertTrue(parsed.contains("The best results are in bold"));
        assertTrue(result.getPages().stream().anyMatch(page ->
                "REFERENCES".equals(page.getSectionType()) && page.getText().contains("[1]")));

        var chunks = new StructureAwareChunkSplitter().split(result, ChunkConfig.builder()
                .chunkSize(1200).chunkOverlap(150).structureAware(true).build());
        var tableChunks = chunks.stream().filter(chunk -> "TABLE".equals(chunk.getContentType())).toList();
        assertEquals(5, tableChunks.size());
        assertTrue(tableChunks.stream().allMatch(chunk ->
                chunk.getTableCaption() != null && !chunk.getTableCaption().isBlank()));
        assertTrue(tableChunks.stream().anyMatch(chunk ->
                chunk.getTableCaption() != null && chunk.getTableCaption().startsWith("Table 3:")
                        && chunk.getContent().contains("ICEWS05-15")
                        && chunk.getContent().contains("368,868")));
        assertTrue(tableChunks.stream().anyMatch(chunk ->
                chunk.getTableCaption() != null && chunk.getTableCaption().startsWith("Table 5:")
                        && chunk.getContent().contains("South_Africa")));
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).getPageNum() >= chunks.get(i - 1).getPageNum(),
                    "Page number regressed at chunk " + i);
        }
    }

    private String contextAround(String text, String token) {
        int index = text.indexOf(token);
        if (index < 0) return "Token not found: " + token;
        return text.substring(Math.max(0, index - 2600), Math.min(text.length(), index + 1000));
    }
    private String tableBlock(String parsed, String captionStart) {
        int caption = parsed.indexOf("[TABLE_CAPTION]" + captionStart);
        int end = caption < 0 ? -1 : parsed.indexOf("[/TABLE]", caption);
        return caption < 0 || end < 0 ? "" : parsed.substring(caption, end);
    }

    private int count(String text, String token) {
        int count = 0;
        for (int from = 0; (from = text.indexOf(token, from)) >= 0; from += token.length()) {
            count++;
        }
        return count;
    }
}