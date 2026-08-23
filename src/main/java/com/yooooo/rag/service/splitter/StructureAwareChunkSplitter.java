package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Splits parsed documents while preserving section, page, and table metadata.
 */
@Component("structureAwareSplitter")
@Slf4j
public class StructureAwareChunkSplitter implements ChunkSplitter {
    private static final Pattern BLOCK_MARKER = Pattern.compile(
            "(?s)(?:\\[TABLE_CAPTION](.*?)\\[/TABLE_CAPTION]\\s*)?\\[TABLE](.*?)\\[/TABLE]|\\[FIGURE_CAPTION](.*?)\\[/FIGURE_CAPTION]");
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("(?is)\\b(?:abstract|摘要)\\b");

    private final SlidingWindowChunkSplitter slidingSplitter = new SlidingWindowChunkSplitter();

    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        List<TextSection> sections = extractSections(parseResult);
        List<ChunkResult> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (TextSection section : sections) {
            if (section.text().isBlank()) {
                continue;
            }
            if ("TABLE".equals(section.contentType())) {
                ParsedTable parsedTable = parseMarkdownTable(section.text());
                if (parsedTable.rows().size() < 2) {
                    chunks.add(ChunkResult.builder()
                            .chunkIndex(chunkIndex++)
                            .content(section.text())
                            .rawContent(section.text())
                            .pageNum(section.pageNum())
                            .sectionTitle(section.title())
                            .sectionType(section.sectionType())
                            .contentType("TEXT")
                            .estimatedTokens(estimateTokens(section.text()))
                            .build());
                    continue;
                }
                String summary = section.text()
                        .replace("[TABLE]", "")
                        .replace("[/TABLE]", "")
                        .strip();
                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunkIndex++)
                        .content(summary)
                        .rawContent(section.text())
                        .tableCaption(section.tableCaption())
                        .pageNum(section.pageNum())
                        .sectionTitle(section.title())
                        .sectionType(section.sectionType())
                        .contentType("TABLE")
                        .estimatedTokens(estimateTokens(summary))
                        .build());
                continue;
            }
            if ("FIGURE_CAPTION".equals(section.contentType())) {
                String figureText = section.text().startsWith("Figure caption:")
                        ? section.text()
                        : "Figure caption: " + section.text();
                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunkIndex++)
                        .content(figureText)
                        .pageNum(section.pageNum())
                        .sectionTitle(section.title())
                        .sectionType(section.sectionType())
                        .contentType("FIGURE_CAPTION")
                        .estimatedTokens(estimateTokens(figureText))
                        .build());
                continue;
            }

            if (section.text().length() <= config.getChunkSize()) {
                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunkIndex++)
                        .content(section.text())
                        .pageNum(section.pageNum())
                        .sectionTitle(section.title())
                        .sectionType(section.sectionType())
                        .contentType(resolveContentType(section.text(), section.contentType()))
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
                            .contentType(section.contentType())
                            .build()))
                    .totalPages(1)
                    .build();

            List<ChunkResult> subChunks = slidingSplitter.split(sectionResult, config);
            for (ChunkResult sub : subChunks) {
                sub.setChunkIndex(chunkIndex++);
                if (sub.getSectionTitle() == null) sub.setSectionTitle(section.title());
                if (sub.getSectionType() == null) sub.setSectionType(section.sectionType());
                if (sub.getContentType() == null) sub.setContentType(resolveContentType(sub.getContent(), section.contentType()));
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
        String currentContentType = null;
        int currentPage = 1;
        boolean pageOneBodyStarted = false;

        for (ParseResult.PageContent page : parseResult.getPages()) {
            if (page.getText() == null || page.getText().isBlank()) {
                continue;
            }

            String pageText = page.getText();
            if (page.getPageNum() == 1 && !pageOneBodyStarted
                    && (page.getSectionType() == null || page.getSectionType().isBlank())) {
                sections.add(new TextSection(null, "FRONTMATTER", "FRONTMATTER",
                        pageText, page.getPageNum(), null));
                continue;
            }
            if (page.getPageNum() != 1 || hasSectionMetadata(page)) {
                pageOneBodyStarted = true;
            }
            if (page.getPageNum() == 1) {
                FrontmatterSplit split = splitFrontMatter(pageText);
                if (split.frontMatter() != null && !split.frontMatter().isBlank()) {
                    sections.add(new TextSection(null, null, "FRONTMATTER", split.frontMatter(), page.getPageNum(), null));
                }
                pageText = split.body();
            }

            boolean pageChanged = current.length() > 0 && currentPage != page.getPageNum();
            boolean sectionChanged = current.length() > 0 && hasSectionMetadata(page)
                    && (!Objects.equals(currentSectionType, page.getSectionType())
                    || !Objects.equals(currentTitle, page.getSectionTitle()));

            if (pageChanged || sectionChanged) {
                addTextSection(sections, currentTitle, currentSectionType, currentContentType, current.toString(), currentPage);
                current = new StringBuilder();
            }

            if (current.length() == 0) {
                currentTitle = page.getSectionTitle();
                currentSectionType = page.getSectionType();
                currentContentType = page.getContentType();
                currentPage = page.getPageNum();
            }

            Matcher matcher = BLOCK_MARKER.matcher(pageText);
            int cursor = 0;
            while (matcher.find()) {
                String before = pageText.substring(cursor, matcher.start()).strip();
                if (!before.isBlank()) {
                    current.append(before).append("\n\n");
                }
                addTextSection(sections, currentTitle, currentSectionType, currentContentType, current.toString(), currentPage);
                current = new StringBuilder();
                currentTitle = page.getSectionTitle();
                currentSectionType = page.getSectionType();
                currentContentType = page.getContentType();
                currentPage = page.getPageNum();

                String tableCaption = cleanMarkerText(matcher.group(1));
                String rawTable = matcher.group(2) == null ? "" : matcher.group(2).strip();
                String figureCaption = cleanMarkerText(matcher.group(3));
                if (figureCaption != null) {
                    sections.add(new TextSection(currentTitle, currentSectionType, "FIGURE_CAPTION", figureCaption, page.getPageNum(), null));
                } else if (!rawTable.isBlank()) {
                    sections.add(new TextSection(currentTitle, currentSectionType, "TABLE", rawTable, page.getPageNum(), tableCaption));
                }
                cursor = matcher.end();
            }

            String tail = pageText.substring(cursor).strip();
            if (!tail.isBlank()) {
                current.append(tail).append("\n\n");
            }
        }

        addTextSection(sections, currentTitle, currentSectionType, currentContentType, current.toString(), currentPage);
        return sections;
    }

    private FrontmatterSplit splitFrontMatter(String text) {
        if (text == null || text.isBlank()) {
            return new FrontmatterSplit(null, "");
        }
        Matcher matcher = FRONTMATTER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return new FrontmatterSplit(null, text);
        }
        String frontMatter = text.substring(0, matcher.start()).strip();
        String body = text.substring(matcher.start()).strip();
        return new FrontmatterSplit(frontMatter, body);
    }

    private void addTextSection(List<TextSection> sections, String title, String sectionType,
                                String contentType, String text, int pageNum) {
        String cleaned = text == null ? "" : text.strip();
        if (!cleaned.isBlank()) {
            sections.add(new TextSection(title, sectionType, contentType, cleaned, pageNum, null));
        }
    }

    private ParsedTable parseMarkdownTable(String rawTable) {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        for (String line : rawTable.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.startsWith("|") || !stripped.endsWith("|")) {
                continue;
            }
            List<String> cells = splitMarkdownRow(stripped);
            if (cells.isEmpty() || isSeparatorRow(cells)) {
                continue;
            }
            if (headers.isEmpty()) {
                headers = cells;
            } else {
                rows.add(cells);
            }
        }
        return new ParsedTable(headers, rows);
    }

    private List<String> splitMarkdownRow(String line) {
        List<String> cells = new ArrayList<>();
        String inner = line.substring(1, line.length() - 1);
        for (String cell : inner.split("\\|")) {
            String cleaned = cell.strip();
            if (!cleaned.isBlank()) {
                cells.add(cleaned);
            }
        }
        return cells;
    }

    private boolean isSeparatorRow(List<String> cells) {
        return cells.stream().allMatch(cell -> cell.matches("^:?-{3,}:?$"));
    }

    private String cleanMarkerText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private String resolveContentType(String text, String fallback) {
        if (text != null && text.contains("[TABLE]")) {
            return "TABLE";
        }
        if (fallback != null && !fallback.isBlank() && !"MIXED".equals(fallback)) {
            return fallback;
        }
        return "TEXT";
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

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    record TextSection(String title, String sectionType, String contentType, String text, int pageNum, String tableCaption) {}
    record ParsedTable(List<String> headers, List<List<String>> rows) {}
    record FrontmatterSplit(String frontMatter, String body) {}
}
