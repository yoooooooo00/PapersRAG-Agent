package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 根据标题和段落结构切分文本，尽量保留语义完整性。
 */
@Component("structureAwareSplitter")
@Slf4j
public class StructureAwareChunkSplitter implements ChunkSplitter {
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,3}\\s+|第[一二三四五六七八九十百\\d]+[章节]|[一二三四五六七八九十]+、|\\d+\\.\\d?\\s+)(.{2,60})$"
    );

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
                        .estimatedTokens(estimateTokens(section.text()))
                        .build());
            } else {
                ParseResult sectionResult = ParseResult.builder()
                        .success(true)
                        .pages(List.of(ParseResult.PageContent.builder()
                                .pageNum(section.pageNum())
                                .text(section.text())
                                .sectionTitle(section.title())
                                .build()))
                        .totalPages(1)
                        .build();

                List<ChunkResult> subChunks = slidingSplitter.split(sectionResult, config);
                for (ChunkResult sub : subChunks) {
                    sub.setChunkIndex(chunkIndex++);
                    if (sub.getSectionTitle() == null) sub.setSectionTitle(section.title());
                    chunks.add(sub);
                }
            }
        }

        return chunks;
    }

    private List<TextSection> extractSections(ParseResult parseResult) {
        List<TextSection> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = null;
        int currentPage = 1;

        for (ParseResult.PageContent page : parseResult.getPages()) {
            String[] lines = page.getText().split("\n");
            for (String line : lines) {
                var matcher = HEADING_PATTERN.matcher(line.strip());
                if (matcher.matches() && current.length() > 50) {
                    sections.add(new TextSection(currentTitle, current.toString().strip(), currentPage));
                    current = new StringBuilder();
                    currentTitle = line.strip();
                    currentPage = page.getPageNum();
                }
                current.append(line).append("\n");
            }
            currentPage = page.getPageNum();
        }

        if (!current.isEmpty()) {
            sections.add(new TextSection(currentTitle, current.toString().strip(), currentPage));
        }

        return sections;
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
/**
 * 结构化切分时识别出的文本段落和标题层级。
 */

    record TextSection(String title, String text, int pageNum) {}
}
