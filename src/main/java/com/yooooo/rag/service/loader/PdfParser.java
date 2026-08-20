package com.yooooo.rag.service.loader;

import java.awt.geom.Rectangle2D;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parses academic PDF files into page text with lightweight section and content metadata.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PdfParser implements DocumentParser {
    private static final String CONTENT_TEXT = "TEXT";
    private static final String CONTENT_TABLE = "TABLE";
    private static final String CONTENT_MIXED = "MIXED";
    private static final String TABLE_CAPTION_START = "[TABLE_CAPTION]";
    private static final String TABLE_CAPTION_END = "[/TABLE_CAPTION]";

    private final AcademicSectionDetector sectionDetector;

    @Value("${rag.parser.pdf.crop-header-points:60}")
    private float cropHeaderPoints;

    @Value("${rag.parser.pdf.crop-footer-points:50}")
    private float cropFooterPoints;

    @Override
    public String supportedType() {
        return "PDF";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            int totalPages = document.getNumberOfPages();
            List<String> rawPages = new ArrayList<>();
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    rawPages.add(extractBodyText(document.getPage(pageNum - 1)));
                } catch (Exception e) {
                    rawPages.add("");
                    log.warn("[PdfParser] Failed to extract page fileName={} page={} reason={}",
                            fileName, pageNum, e.getMessage());
                }
            }

            Map<String, Integer> repeatedLineCounts = countRepeatedCandidateLines(rawPages);
            List<ParseResult.PageContent> pages = new ArrayList<>();
            String currentSectionTitle = null;
            String currentSectionType = null;

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                String rawText = rawPages.get(pageNum - 1);
                String filteredRawText = filterNoiseLines(rawText, repeatedLineCounts, totalPages);
                String text = cleanText(filteredRawText);
                if (text.isBlank()) {
                    log.debug("[PdfParser] Empty text page skipped fileName={} page={}", fileName, pageNum);
                    continue;
                }

                AcademicSectionDetector.SectionMatch match = sectionDetector.detectFromText(text);
                if (match.sectionType() != null) {
                    currentSectionTitle = match.sectionTitle();
                    currentSectionType = match.sectionType();
                }

                String normalizedText = normalizeTableRuns(cleanTextPreservingColumns(filteredRawText));
                pages.add(ParseResult.PageContent.builder()
                        .pageNum(pageNum)
                        .text(normalizedText)
                        .sectionTitle(currentSectionTitle)
                        .sectionType(currentSectionType)
                        .contentType(resolvePageContentType(normalizedText))
                        .build());
            }

            if (pages.isEmpty()) {
                return ParseResult.failure("PDF parsing produced no text. The file may be scanned and require OCR.");
            }

            String title = extractTitle(pages);
            log.info("[PdfParser] Parsed PDF fileName={} totalPages={} textPages={} title={}",
                    fileName, totalPages, pages.size(), title);

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(totalPages)
                    .title(title)
                    .build();
        } catch (Exception e) {
            log.error("[PdfParser] Failed to parse PDF fileName={} reason={}", fileName, e.getMessage(), e);
            return ParseResult.failure("PDF parsing failed: " + e.getMessage());
        }
    }

    private String extractBodyText(PDPage page) throws Exception {
        PDRectangle cropBox = page.getCropBox();
        float pageWidth = cropBox.getWidth();
        float pageHeight = cropBox.getHeight();
        float top = clamp(cropHeaderPoints, 0, pageHeight);
        float bottom = clamp(cropFooterPoints, 0, pageHeight - top);
        float bodyHeight = Math.max(1, pageHeight - top - bottom);

        Rectangle2D.Float bodyRegion = new Rectangle2D.Float(
                cropBox.getLowerLeftX(),
                cropBox.getLowerLeftY() + top,
                pageWidth,
                bodyHeight
        );

        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        stripper.setSortByPosition(true);
        stripper.addRegion("body", bodyRegion);
        stripper.extractRegions(page);
        return stripper.getTextForRegion("body");
    }

    private Map<String, Integer> countRepeatedCandidateLines(List<String> rawPages) {
        Map<String, Integer> counts = new HashMap<>();
        for (String page : rawPages) {
            List<String> seenOnPage = new ArrayList<>();
            for (String line : splitLines(page)) {
                String normalized = normalizeNoiseLine(line);
                if (isRepeatedLineCandidate(normalized) && !seenOnPage.contains(normalized)) {
                    counts.merge(normalized, 1, Integer::sum);
                    seenOnPage.add(normalized);
                }
            }
        }
        return counts;
    }

    private String filterNoiseLines(String rawText, Map<String, Integer> repeatedLineCounts, int totalPages) {
        StringBuilder output = new StringBuilder();
        int repeatedThreshold = Math.max(3, Math.min(totalPages, (int) Math.ceil(totalPages * 0.35)));
        for (String line : splitLines(rawText)) {
            String stripped = line.strip();
            String normalized = normalizeNoiseLine(stripped);
            if (stripped.isBlank()) {
                output.append('\n');
                continue;
            }
            if (isPageNumber(stripped) || isCommonHeaderFooterNoise(stripped)) {
                continue;
            }
            if (repeatedLineCounts.getOrDefault(normalized, 0) >= repeatedThreshold) {
                continue;
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n"));
    }

    private boolean isRepeatedLineCandidate(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.length() <= 140;
    }

    private String normalizeNoiseLine(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private boolean isPageNumber(String line) {
        String value = line.strip();
        return value.matches("^\\d{1,4}$")
                || value.matches("^(page|p\\.)\\s*\\d{1,4}$")
                || value.matches("^\\d{1,4}\\s*/\\s*\\d{1,4}$")
                || value.matches("^-\\s*\\d{1,4}\\s*-$");
    }

    private boolean isCommonHeaderFooterNoise(String line) {
        String lower = line.toLowerCase(Locale.ROOT).strip();
        if (lower.length() > 180) {
            return false;
        }
        return lower.contains("proceedings of")
                || lower.contains("conference on neural information processing systems")
                || lower.contains("copyright")
                || lower.contains("all rights reserved")
                || lower.matches("^arxiv:\\d{4}\\.\\d{4,5}.*")
                || lower.matches("^preprint.*")
                || lower.matches("^under review.*")
                || lower.matches("^published as.*");
    }

    private String normalizeTableRuns(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder output = new StringBuilder();
        List<List<String>> tableRows = new ArrayList<>();
        String pendingCaption = null;

        for (String line : lines) {
            String caption = extractTableCaption(line);
            if (caption != null) {
                flushTable(output, tableRows, pendingCaption);
                pendingCaption = caption;
                continue;
            }

            List<String> row = parseTableRow(line);
            if (row.size() >= 2) {
                tableRows.add(row);
                continue;
            }

            boolean hadTableRows = !tableRows.isEmpty();
            flushTable(output, tableRows, pendingCaption);
            if (hadTableRows) {
                pendingCaption = null;
            }
            if (!line.isBlank()) {
                output.append(line.strip()).append('\n');
            } else if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
                output.append('\n');
            }
        }
        flushTable(output, tableRows, pendingCaption);
        return cleanText(output.toString());
    }

    private void flushTable(StringBuilder output, List<List<String>> tableRows, String caption) {
        if (tableRows.isEmpty()) {
            return;
        }
        if (tableRows.size() < 2) {
            for (List<String> row : tableRows) {
                output.append(String.join(" ", row)).append('\n');
            }
            tableRows.clear();
            return;
        }

        int columnCount = tableRows.stream().mapToInt(List::size).max().orElse(0);
        if (columnCount < 2) {
            tableRows.clear();
            return;
        }

        if (caption != null && !caption.isBlank()) {
            output.append(TABLE_CAPTION_START).append(caption.strip()).append(TABLE_CAPTION_END).append('\n');
        }
        output.append("[TABLE]\n");
        appendMarkdownRow(output, tableRows.get(0), columnCount);
        appendSeparatorRow(output, columnCount);
        for (int i = 1; i < tableRows.size(); i++) {
            appendMarkdownRow(output, tableRows.get(i), columnCount);
        }
        output.append("[/TABLE]\n");
        tableRows.clear();
    }

    private String extractTableCaption(String line) {
        if (line == null) {
            return null;
        }
        String stripped = line.strip();
        if (stripped.length() < 8 || stripped.length() > 300) {
            return null;
        }
        if (stripped.matches("(?i)^table\\s+[ivxlcdm0-9]+\\s*[:.\\-]?.*")) {
            return stripped;
        }
        return null;
    }

    private List<String> parseTableRow(String line) {
        List<String> cells = new ArrayList<>();
        String stripped = line == null ? "" : line.strip();
        if (stripped.length() < 8 || sectionDetector.detect(stripped).sectionType() != null) {
            return cells;
        }
        if (stripped.startsWith("|") && stripped.endsWith("|") && stripped.indexOf('|', 1) > 0) {
            for (String cell : stripped.split("\\|")) {
                addCell(cells, cell);
            }
            return cells;
        }
        if (!hasTableSignal(stripped)) {
            return cells;
        }
        for (String cell : stripped.split("\\t+| {2,}")) {
            addCell(cells, cell);
        }
        return cells;
    }

    private boolean hasTableSignal(String line) {
        int wideSpaces = 0;
        for (int i = 0; i < line.length() - 1; i++) {
            if (line.charAt(i) == ' ' && line.charAt(i + 1) == ' ') {
                wideSpaces++;
            }
        }
        boolean hasNumber = line.matches(".*\\d.*");
        boolean hasMultipleColumns = wideSpaces >= 1 || line.contains("\t");
        return hasMultipleColumns && (hasNumber || line.matches(".*[A-Za-z].*[A-Za-z].*"));
    }

    private void addCell(List<String> cells, String rawCell) {
        String cell = rawCell == null ? "" : rawCell.strip();
        if (!cell.isBlank()) {
            cells.add(cell.replace("|", "/"));
        }
    }

    private void appendMarkdownRow(StringBuilder output, List<String> row, int columnCount) {
        output.append('|');
        for (int i = 0; i < columnCount; i++) {
            String value = i < row.size() ? row.get(i) : "";
            output.append(' ').append(value).append(' ').append('|');
        }
        output.append('\n');
    }

    private void appendSeparatorRow(StringBuilder output, int columnCount) {
        output.append('|');
        for (int i = 0; i < columnCount; i++) {
            output.append(" --- |");
        }
        output.append('\n');
    }

    private String resolvePageContentType(String text) {
        if (text == null || !text.contains("[TABLE]")) {
            return CONTENT_TEXT;
        }
        String withoutTables = text.replaceAll("(?s)\\[TABLE].*?\\[/TABLE]", "")
                .replaceAll("(?s)\\[TABLE_CAPTION].*?\\[/TABLE_CAPTION]", "")
                .strip();
        return withoutTables.isBlank() ? CONTENT_TABLE : CONTENT_MIXED;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    private String cleanTextPreservingColumns(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private String cleanText(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private String extractTitle(List<ParseResult.PageContent> pages) {
        if (pages.isEmpty()) return null;
        String[] lines = pages.get(0).getText().split("\\R");
        List<String> candidates = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                if (!candidates.isEmpty()) break;
                continue;
            }
            if (line.equals("[TABLE]") || line.equals("[/TABLE]") || line.startsWith("|")) {
                continue;
            }
            if (line.startsWith(TABLE_CAPTION_START)) {
                continue;
            }
            if (sectionDetector.detect(line).sectionType() != null) {
                break;
            }
            if (looksLikeAuthorOrMetadata(line)) {
                if (!candidates.isEmpty()) break;
                continue;
            }
            if (line.length() >= 6 && line.length() <= 180) {
                candidates.add(line);
                if (candidates.size() >= 3) break;
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return String.join(" ", candidates).strip();
    }

    private boolean looksLikeAuthorOrMetadata(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("@")
                || lower.contains("university")
                || lower.contains("institute")
                || lower.contains("department")
                || lower.contains("arxiv")
                || lower.startsWith("http")
                || lower.matches(".*\\b20\\d{2}\\b.*");
    }
}
