package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
    private static final Set<String> KNOWN_METRICS = Set.of(
            "MRR", "HITS@1", "HITS@3", "HITS@5", "HITS@10", "HIT@1", "HIT@3", "HIT@10",
            "ACC", "ACCURACY", "F1", "PRECISION", "RECALL", "AUC", "MAP", "NDCG", "MAE", "RMSE");
    private static final Pattern DATASET_PATTERN = Pattern.compile(
            "(?i)\\b(ICEWS(?:05-15|14|18)?|GDELT|YAGO(?:11k)?|WIKI(?:DATA)?|FB15K(?:-237)?|WN18RR|NELL|UMLS|DBPEDIA|ACLED|WIKIDATA(?:12k)?)\\b");
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
                String summary = buildTableSummary(section);
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

        for (ParseResult.PageContent page : parseResult.getPages()) {
            if (page.getText() == null || page.getText().isBlank()) {
                continue;
            }

            String pageText = page.getText();
            if (page.getPageNum() == 1) {
                FrontmatterSplit split = splitFrontMatter(pageText);
                if (split.frontMatter() != null && !split.frontMatter().isBlank()) {
                    sections.add(new TextSection(null, null, "FRONTMATTER", split.frontMatter(), page.getPageNum(), null));
                }
                pageText = split.body();
            }

            boolean sectionChanged = current.length() > 0
                    && hasSectionMetadata(page)
                    && (!Objects.equals(currentSectionType, page.getSectionType())
                    || !Objects.equals(currentTitle, page.getSectionTitle()));

            if (sectionChanged) {
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

    private String buildTableSummary(TextSection section) {
        ParsedTable table = parseMarkdownTable(section.text());
        Set<String> datasets = extractDatasets(section.tableCaption(), section.text());
        Set<String> metrics = extractMetrics(table.headers(), section.tableCaption(), section.text());
        Set<String> methods = extractMethods(table);
        Set<String> keywords = inferTableKeywords(section.tableCaption(), section.title(), section.sectionType());

        List<String> lines = new ArrayList<>();
        addLine(lines, "Table caption", section.tableCaption());
        addLine(lines, "Section", section.title());
        addLine(lines, "Section type", section.sectionType());
        addLine(lines, "Page", String.valueOf(section.pageNum()));
        if (!table.headers().isEmpty()) {
            lines.add("Header columns: " + String.join(", ", table.headers()) + ".");
        }
        if (!table.rows().isEmpty()) {
            lines.add("Row count: " + table.rows().size() + ".");
        }
        if (!datasets.isEmpty()) lines.add("Datasets mentioned: " + String.join(", ", datasets) + ".");
        if (!metrics.isEmpty()) lines.add("Metrics mentioned: " + String.join(", ", metrics) + ".");
        if (!methods.isEmpty()) lines.add("Methods or variants mentioned: " + String.join(", ", methods) + ".");
        if (!keywords.isEmpty()) lines.add("Retrieval keywords: " + String.join(", ", keywords) + ".");
        lines.add("This is a table extracted from the paper; use the raw table as evidence for exact values.");
        return String.join("\n", lines).strip();
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

    private Set<String> extractDatasets(String caption, String rawTable) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = DATASET_PATTERN.matcher((caption == null ? "" : caption) + "\n" + rawTable);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private Set<String> extractMetrics(List<String> headers, String caption, String rawTable) {
        Set<String> values = new LinkedHashSet<>();
        String text = String.join(" ", headers) + " " + nullToBlank(caption) + " " + rawTable;
        String upper = text.toUpperCase(Locale.ROOT);
        for (String metric : KNOWN_METRICS) {
            if (upper.contains(metric)) {
                values.add(metric);
            }
        }
        return values;
    }

    private Set<String> extractMethods(ParsedTable table) {
        Set<String> values = new LinkedHashSet<>();
        if (table.rows().isEmpty()) {
            return values;
        }
        int methodColumn = resolveMethodColumn(table.headers());
        if (methodColumn < 0) {
            return values;
        }
        for (List<String> row : table.rows()) {
            if (methodColumn < row.size()) {
                String method = row.get(methodColumn).replace("*", "").strip();
                if (looksLikeMethodName(method)) {
                    values.add(method);
                }
            }
            if (values.size() >= 20) {
                break;
            }
        }
        return values;
    }

    private int resolveMethodColumn(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            String lower = headers.get(i).toLowerCase(Locale.ROOT);
            if (lower.contains("method") || lower.contains("model") || lower.contains("approach")
                    || lower.contains("variant") || lower.contains("setting")) {
                return i;
            }
        }
        return headers.isEmpty() ? -1 : 0;
    }

    private boolean looksLikeMethodName(String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            return false;
        }
        return !value.matches("^[0-9.,%+ -]+$");
    }

    private Set<String> inferTableKeywords(String caption, String sectionTitle, String sectionType) {
        Set<String> keywords = new LinkedHashSet<>();
        keywords.add("table");
        keywords.add("experimental results");
        String text = (nullToBlank(caption) + " " + nullToBlank(sectionTitle) + " " + nullToBlank(sectionType)).toLowerCase(Locale.ROOT);
        if (text.contains("ablation")) keywords.add("ablation study");
        if (text.contains("baseline") || text.contains("comparison") || text.contains("compare")) keywords.add("baseline comparison");
        if (text.contains("dataset")) keywords.add("dataset statistics");
        if (text.contains("result") || text.contains("experiment") || text.contains("evaluation")) keywords.add("performance comparison");
        return keywords;
    }

    private void addLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            lines.add(label + ": " + value.strip());
        }
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
