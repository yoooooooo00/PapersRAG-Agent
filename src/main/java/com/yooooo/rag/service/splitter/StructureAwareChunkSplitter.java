package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Splits parsed documents while preserving section metadata from the PDF parser.
 */
@Component("structureAwareSplitter")
@Slf4j
public class StructureAwareChunkSplitter implements ChunkSplitter {
    private final SlidingWindowChunkSplitter slidingSplitter = new SlidingWindowChunkSplitter();

    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        List<TextSection> sections = extractSections(parseResult);
        List<ChunkResult> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (TextSection section : sections) {
            if (section.text().length() <= config.getChunkSize()) {
                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunkIndex++)
                        .content(section.text())
                        .pageNum(section.pageNum())
                        .sectionTitle(section.title())
                        .sectionType(section.sectionType())
                        .estimatedTokens(estimateTokens(section.text()))
                        .build());
                continue;
            }

            ParseResult sectionResult = ParseResult.builder()
                    .success(true)
                    .pages(List.of(ParseResult.PageContent.builder()
                            .pageNum(section.pageNum())
                            .text(section.text())
                            .sectionTitle(section.title())
                            .sectionType(section.sectionType())
                            .build()))
                    .totalPages(1)
                    .build();

            List<ChunkResult> subChunks = slidingSplitter.split(sectionResult, config);
            for (ChunkResult sub : subChunks) {
                sub.setChunkIndex(chunkIndex++);
                if (sub.getSectionTitle() == null) sub.setSectionTitle(section.title());
                if (sub.getSectionType() == null) sub.setSectionType(section.sectionType());
                chunks.add(sub);
            }
        }

        return chunks;
    }

    private List<TextSection> extractSections(ParseResult parseResult) {
        List<TextSection> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = null;
        String currentSectionType = null;
        int currentPage = 1;

        for (ParseResult.PageContent page : parseResult.getPages()) {
            if (page.getText() == null || page.getText().isBlank()) {
                continue;
            }

            boolean sectionChanged = current.length() > 0
                    && hasSectionMetadata(page)
                    && (!Objects.equals(currentSectionType, page.getSectionType())
                    || !Objects.equals(currentTitle, page.getSectionTitle()));

            if (sectionChanged) {
                sections.add(new TextSection(currentTitle, currentSectionType, current.toString().strip(), currentPage));
                current = new StringBuilder();
            }

            if (current.length() == 0) {
                currentTitle = page.getSectionTitle();
                currentSectionType = page.getSectionType();
                currentPage = page.getPageNum();
            }

            current.append(page.getText()).append("\n\n");
        }

        if (!current.isEmpty()) {
            sections.add(new TextSection(currentTitle, currentSectionType, current.toString().strip(), currentPage));
        }

        return sections;
    }

    private boolean hasSectionMetadata(ParseResult.PageContent page) {
        return (page.getSectionType() != null && !page.getSectionType().isBlank())
                || (page.getSectionTitle() != null && !page.getSectionTitle().isBlank());
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chinese = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chinese++;
            else if (!Character.isWhitespace(c)) other++;
        }
        return (int) (chinese * 1.5 + other * 0.3);
    }

    record TextSection(String title, String sectionType, String text, int pageNum) {}
}