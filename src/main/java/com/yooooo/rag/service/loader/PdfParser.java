package com.yooooo.rag.service.loader;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;
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
    private static final String FIGURE_CAPTION_START = "[FIGURE_CAPTION]";
    private static final String FIGURE_CAPTION_END = "[/FIGURE_CAPTION]";
    private static final Pattern INLINE_FIGURE_CAPTION = Pattern.compile(
            "(?i)\\b(?:figure|fig\\.)\\s+[ivxlcdm0-9]+\\s*[:.\\-]\\s*");
    private static final Pattern NUMERIC_VALUE = Pattern.compile(
            "^[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?%?[*\\u2020\\u2021]*$");

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

                String normalizedText = normalizeTableRuns(cleanTextPreservingColumns(filteredRawText));
                List<PageSection> pageSections = splitPageSections(
                        normalizedText, currentSectionTitle, currentSectionType);
                for (PageSection section : pageSections) {
                    pages.add(ParseResult.PageContent.builder()
                            .pageNum(pageNum)
                            .text(section.text())
                            .sectionTitle(section.title())
                            .sectionType(section.sectionType())
                            .contentType(resolvePageContentType(section.text()))
                            .build());
                }
                if (!pageSections.isEmpty()) {
                    PageSection last = pageSections.get(pageSections.size() - 1);
                    currentSectionTitle = last.title();
                    currentSectionType = last.sectionType();
                }
            }

            if (pages.isEmpty()) {
                return ParseResult.failure("PDF parsing produced no text. The file may be scanned and require OCR.");
            }

            String title = extractTitle(rawPages.isEmpty() ? null : rawPages.get(0));
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

    private List<PageSection> splitPageSections(String text, String inheritedTitle, String inheritedType) {
        List<PageSection> sections = new ArrayList<>();
        String currentTitle = inheritedTitle;
        String currentType = inheritedType;
        StringBuilder current = new StringBuilder();

        for (String line : text.split("\\R", -1)) {
            AcademicSectionDetector.SectionMatch match = sectionDetector.detect(line);
            if (match.hasHeading()) {
                addPageSection(sections, current, currentTitle, currentType);
                current = new StringBuilder();
                currentTitle = match.sectionTitle();
                if (match.sectionType() != null) currentType = match.sectionType();
            }
            if (!line.isBlank()) {
                current.append(line.strip()).append('\n');
            }
        }
        addPageSection(sections, current, currentTitle, currentType);
        return sections;
    }

    private void addPageSection(List<PageSection> sections, StringBuilder text,
                                String title, String sectionType) {
        String value = text.toString().strip();
        if (!value.isBlank()) {
            sections.add(new PageSection(title, sectionType, value));
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

        PDFTextStripperByArea stripper = new LayoutPreservingTextStripperByArea();
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
        int repeatedThreshold = totalPages <= 5
                ? 2
                : Math.max(3, Math.min(totalPages, (int) Math.ceil(totalPages * 0.35)));
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
                || lower.contains("journal of")
                || lower.contains("vol.")
                || lower.contains("volume")
                || lower.contains("issue")
                || lower.contains("pp.")
                || lower.contains("accepted manuscript")
                || lower.contains("author manuscript")
                || lower.contains("published in")
                || lower.matches("^arxiv:\\d{4}\\.\\d{4,5}.*")
                || lower.matches("^preprint.*")
                || lower.matches("^under review.*")
                || lower.matches("^published as.*");
    }

    private String[] isolateInlineFigureCaptions(String[] sourceLines) {
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(sourceLines));
        List<String> output = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher marker = INLINE_FIGURE_CAPTION.matcher(line);
            if (!marker.find() || marker.start() == 0) {
                output.add(line);
                continue;
            }

            String prefix = line.substring(0, marker.start()).stripTrailing();
            StringBuilder caption = new StringBuilder(line.substring(marker.start()).strip());
            int captionColumn = marker.start();
            for (int lookAhead = i + 1;
                 lookAhead < lines.size() && lookAhead <= i + 4 && !endsWithSentencePunctuation(caption.toString());
                 lookAhead++) {
                CaptionContinuation continuation = captionContinuationAtColumn(
                        lines.get(lookAhead), captionColumn, caption.toString());
                if (continuation == null) continue;
                appendCaptionFragment(caption, continuation.captionPart());
                lines.set(lookAhead, continuation.bodyPart());
            }
            if (!prefix.isBlank()) output.add(prefix);
            output.add(caption.toString());
        }
        return output.toArray(String[]::new);
    }

    private void appendCaptionFragment(StringBuilder caption, String fragment) {
        String value = fragment == null ? "" : fragment.strip();
        if (value.isBlank()) return;
        if (!caption.isEmpty() && caption.charAt(caption.length() - 1) == '-'
                && Character.isLowerCase(value.codePointAt(0))) {
            caption.setLength(caption.length() - 1);
            caption.append(value);
            return;
        }
        if (!caption.isEmpty()) caption.append(' ');
        caption.append(value);
    }
    private CaptionContinuation captionContinuationAtColumn(String line, int captionColumn, String caption) {
        if (line == null || line.isBlank()) return null;
        Matcher gaps = Pattern.compile(" {2,}").matcher(line);
        CaptionContinuation best = null;
        int bestDistance = Integer.MAX_VALUE;
        while (gaps.find()) {
            String right = line.substring(gaps.end()).strip();
            if (right.isBlank()) continue;
            int distance = Math.abs(gaps.end() - captionColumn);
            if (distance <= 24 && distance < bestDistance) {
                best = new CaptionContinuation(line.substring(0, gaps.start()).stripTrailing(), right);
                bestDistance = distance;
            }
        }
        if (best != null) return best;

        int leadingSpaces = line.length() - line.stripLeading().length();
        if (captionColumn >= 8 && leadingSpaces >= Math.max(4, captionColumn - 12)) {
            return new CaptionContinuation("", line.strip());
        }
        String stripped = line.strip();
        if (caption.endsWith("-") && stripped.length() <= 90) {
            return new CaptionContinuation("", stripped);
        }
        if (stripped.length() <= 80 && endsWithSentencePunctuation(stripped)
                && !sectionDetector.detect(stripped).hasHeading()) {
            return new CaptionContinuation("", stripped);
        }
        return null;
    }
    private String normalizeTableRuns(String text) {
        String[] lines = isolateInlineFigureCaptions(text.split("\\R", -1));
        StringBuilder output = new StringBuilder();
        List<List<String>> tableRows = new ArrayList<>();
        String pendingCaption = null;

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            String figureCaption = extractFigureCaption(line);
            if (figureCaption != null) {
                while (lineIndex + 1 < lines.length
                        && isCaptionContinuation(figureCaption, lines[lineIndex + 1])) {
                    figureCaption = figureCaption + " " + lines[++lineIndex].strip();
                }
                flushTable(output, tableRows, pendingCaption);
                pendingCaption = null;
                output.append(FIGURE_CAPTION_START).append(figureCaption).append(FIGURE_CAPTION_END).append('\n');
                continue;
            }

            String caption = extractTableCaption(line);
            if (caption != null) {
                while (lineIndex + 1 < lines.length
                        && isCaptionContinuation(caption, lines[lineIndex + 1])) {
                    caption = caption + " " + lines[++lineIndex].strip();
                }
                if (!tableRows.isEmpty() && isStructurallyConsistentTable(tableRows, false)) {
                    flushTable(output, tableRows, caption);
                    pendingCaption = null;
                } else {
                    flushTable(output, tableRows, pendingCaption);
                    pendingCaption = caption;
                }
                continue;
            }

            boolean dataRowsStarted = tableRows.stream().anyMatch(row -> numericCellCount(row) >= 2);
            List<String> row = parseTableRow(
                    line, !tableRows.isEmpty() || pendingCaption != null, !dataRowsStarted);
            if (dataRowsStarted && row.size() > 3 && numericCellCount(row) == 0) {
                row = List.of();
            }
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

        if (!isStructurallyConsistentTable(tableRows, caption != null)) {
            for (List<String> row : tableRows) {
                output.append(String.join(" ", row)).append((char) 10);
            }
            tableRows.clear();
            return;
        }
        List<List<String>> normalizedRows = normalizeTableShape(tableRows);
        tableRows.clear();
        tableRows.addAll(normalizedRows);

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

    private boolean isStructurallyConsistentTable(List<List<String>> rows, boolean hasCaption) {
        if (rows.size() < (hasCaption ? 2 : 3)) return false;
        if (hasCaption) return true;

        Map<Integer, Integer> dataWidths = new HashMap<>();
        int dataRows = 0;
        for (List<String> row : rows) {
            if (!isStrongDataRow(row)) continue;
            dataRows++;
            dataWidths.merge(row.size(), 1, Integer::sum);
        }
        if (dataRows < 2) return false;

        int modalFrequency = dataWidths.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return modalFrequency >= 2 && modalFrequency * 10 >= dataRows * 8;
    }

    private boolean isStrongDataRow(List<String> row) {
        if (row == null || row.size() < 2) return false;
        int trailingValues = trailingValueCount(row);
        return trailingValues >= 2 || (row.size() == 2 && trailingValues == 1);
    }

    private int trailingValueCount(List<String> row) {
        int count = 0;
        for (int index = row.size() - 1; index >= 0; index--) {
            if (!isValueCell(row.get(index))) break;
            count++;
        }
        return count;
    }    private boolean isCaptionContinuation(String caption, String nextLine) {
        if (caption == null || nextLine == null || caption.length() >= 900) return false;
        String next = nextLine.strip();
        if (next.isBlank() || next.length() > 320
                || extractFigureCaption(next) != null || extractTableCaption(next) != null
                || sectionDetector.detect(next).hasHeading()) {
            return false;
        }
        if (endsWithSentencePunctuation(caption)) return false;
        return next.length() >= 60 || startsLikeSentenceContinuation(next);
    }

    private boolean startsLikeSentenceContinuation(String value) {
        if (value == null) return false;
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            if (Character.isWhitespace(codePoint) || codePoint == 0x22 || codePoint == 0x27
                    || codePoint == 0x28 || codePoint == 0x5b
                    || codePoint == 0x2018 || codePoint == 0x201c) {
                offset += Character.charCount(codePoint);
                continue;
            }
            return Character.isLowerCase(codePoint);
        }
        return false;
    }
    private boolean endsWithSentencePunctuation(String value) {
        String stripped = value == null ? "" : value.strip();
        while (!stripped.isEmpty()) {
            int last = stripped.codePointBefore(stripped.length());
            if (last == 0x22 || last == 0x27 || last == 0x29 || last == 0x5d
                    || last == 0x2019 || last == 0x201d) {
                stripped = stripped.substring(0, stripped.offsetByCodePoints(stripped.length(), -1)).stripTrailing();
                continue;
            }
            return last == 0x2e || last == 0x21 || last == 0x3f;
        }
        return false;
    }
    /**
     * Repairs over-segmented rows using only shape evidence shared by the table itself.
     * No dataset names, metric names, languages, or document-specific labels are consulted.
     */
    private List<List<String>> normalizeTableShape(List<List<String>> sourceRows) {
        List<List<String>> rows = new ArrayList<>();
        for (List<String> sourceRow : sourceRows) rows.add(new ArrayList<>(sourceRow));
        mergeRepeatedTrailingUnits(rows);

        int firstDataRow = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (trailingValueCount(rows.get(i)) >= 2) {
                firstDataRow = i;
                break;
            }
        }
        if (firstDataRow < 0) return normalizeTextOnlyTable(rows);

        int valueColumns = modalTrailingValueCount(rows, firstDataRow);
        if (valueColumns < 2) return rows;
        if (firstDataRow == 1) {
            List<String> header = collapseRepeatedHeaderSuffixes(rows.get(0));
            rows.set(0, new ArrayList<>(header));
            int targetWidth = header.size();
            for (int i = firstDataRow; i < rows.size(); i++) {
                rows.set(i, alignDataRow(rows.get(i), targetWidth - valueColumns, valueColumns));
            }
            return rows;
        }

        int metricHeaderIndex = firstDataRow - 1;
        List<String> metricHeader = rows.get(metricHeaderIndex);
        int period = smallestRepeatingPeriod(metricHeader);
        int groupCount = period > 0 && metricHeader.size() % period == 0
                ? metricHeader.size() / period : 1;

        int primaryHeaderIndex = metricHeaderIndex == 0 ? 1 : 0;
        List<String> primaryHeader = rows.get(primaryHeaderIndex);
        int prefixColumns = primaryHeader.size() - groupCount;
        if (prefixColumns < 1 || prefixColumns > 6) {
            prefixColumns = Math.max(1, modalDataWidth(rows, firstDataRow) - valueColumns);
        }
        int targetWidth = prefixColumns + valueColumns;

        rows.set(metricHeaderIndex, padLeft(metricHeader, targetWidth));
        if (primaryHeader.size() == prefixColumns + groupCount && groupCount >= 1) {
            rows.set(primaryHeaderIndex, expandGroupedHeader(primaryHeader, prefixColumns,
                    groupCount, valueColumns, targetWidth));
        } else {
            rows.set(primaryHeaderIndex, padRight(primaryHeader, targetWidth));
        }
        for (int i = 0; i < firstDataRow; i++) {
            if (i != metricHeaderIndex && i != primaryHeaderIndex) {
                rows.set(i, padRight(rows.get(i), targetWidth));
            }
        }
        for (int i = firstDataRow; i < rows.size(); i++) {
            rows.set(i, alignDataRow(rows.get(i), prefixColumns, valueColumns));
        }
        return rows;
    }

    private void mergeRepeatedTrailingUnits(List<List<String>> rows) {
        Map<String, Integer> units = new HashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 2) continue;
            String unit = normalizeTableToken(row.get(row.size() - 1)).toLowerCase(Locale.ROOT);
            String number = normalizeTableToken(row.get(row.size() - 2));
            if (!unit.isBlank() && !isValueCell(unit) && NUMERIC_VALUE.matcher(number).matches()) {
                units.merge(unit, 1, Integer::sum);
            }
        }
        for (List<String> row : rows) {
            if (row.size() < 2) continue;
            String unit = normalizeTableToken(row.get(row.size() - 1)).toLowerCase(Locale.ROOT);
            String number = normalizeTableToken(row.get(row.size() - 2));
            if (units.getOrDefault(unit, 0) >= 2 && NUMERIC_VALUE.matcher(number).matches()) {
                mergeCells(row, row.size() - 2);
            }
        }
    }
    private int modalTrailingValueCount(List<List<String>> rows, int firstDataRow) {
        Map<Integer, Integer> frequencies = new HashMap<>();
        for (int i = firstDataRow; i < rows.size(); i++) {
            int count = trailingValueCount(rows.get(i));
            if (count >= 2) frequencies.merge(count, 1, Integer::sum);
        }
        int value = -1;
        int frequency = -1;
        for (Map.Entry<Integer, Integer> entry : frequencies.entrySet()) {
            if (entry.getValue() > frequency || (entry.getValue() == frequency && entry.getKey() > value)) {
                value = entry.getKey();
                frequency = entry.getValue();
            }
        }
        return value;
    }

    private int smallestRepeatingPeriod(List<String> cells) {
        int size = cells.size();
        for (int period = 1; period <= size / 2; period++) {
            if (size % period != 0) continue;
            boolean repeats = true;
            for (int i = period; i < size; i++) {
                if (!normalizeTableToken(cells.get(i)).equalsIgnoreCase(
                        normalizeTableToken(cells.get(i % period)))) {
                    repeats = false;
                    break;
                }
            }
            if (repeats) return period;
        }
        return size;
    }

    private List<String> alignDataRow(List<String> source, int prefixColumns, int valueColumns) {
        List<String> row = mergeStandaloneReferences(source);
        int suffixStart = row.size();
        int suffixCount = 0;
        for (int i = row.size() - 1; i >= 0 && isValueCell(row.get(i)); i--) {
            suffixStart = i;
            suffixCount++;
        }
        if (suffixCount != valueColumns) return padRight(row, Math.max(row.size(), prefixColumns + valueColumns));

        List<String> prefix = new ArrayList<>(row.subList(0, suffixStart));
        List<String> values = new ArrayList<>(row.subList(suffixStart, row.size()));
        while (prefix.size() > prefixColumns) {
            int mergeAt = prefixColumns == 1 ? 0 : Math.max(0, prefix.size() - prefixColumns);
            mergeCells(prefix, Math.min(mergeAt, prefix.size() - 2));
        }
        while (prefix.size() < prefixColumns) prefix.add(0, "");
        prefix.addAll(values);
        return prefix;
    }

    private List<String> mergeStandaloneReferences(List<String> source) {
        List<String> row = new ArrayList<>(source);
        for (int i = 1; i < row.size(); i++) {
            String value = row.get(i).strip();
            if (value.matches("^[\\[（(]\\s*\\d+(?:\\s*[,;]\\s*\\d+)*\\s*[\\]）)]$")) {
                mergeCells(row, i - 1);
                i--;
            }
        }
        return row;
    }

    private boolean isValueCell(String cell) {
        String token = normalizeTableToken(cell);
        return NUMERIC_VALUE.matcher(token).matches() || isMissingValueMarker(token)
                || token.matches("^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)\\s+[^\\d\\s]+$");
    }

    private List<String> padLeft(List<String> source, int width) {
        List<String> result = new ArrayList<>();
        for (int i = source.size(); i < width; i++) result.add("");
        result.addAll(source);
        return result;
    }

    private List<String> padRight(List<String> source, int width) {
        List<String> result = new ArrayList<>(source);
        while (result.size() < width) result.add("");
        return result;
    }

    private List<String> expandGroupedHeader(List<String> source, int prefixColumns,
                                              int groupCount, int valueColumns, int width) {
        List<String> result = new ArrayList<>(source.subList(0, prefixColumns));
        int groupWidth = Math.max(1, valueColumns / groupCount);
        for (int group = 0; group < groupCount; group++) {
            result.add(source.get(prefixColumns + group));
            for (int i = 1; i < groupWidth; i++) result.add("");
        }
        return padRight(result, width);
    }
    private List<List<String>> normalizeTextOnlyTable(List<List<String>> rows) {
        Map<Integer, Integer> widths = new HashMap<>();
        for (List<String> row : rows) widths.merge(row.size(), 1, Integer::sum);
        int modalFrequency = widths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (widths.size() <= 2 && modalFrequency * 10 >= rows.size() * 7) return rows;

        List<List<String>> normalized = new ArrayList<>();
        normalized.add(List.of("Row", "Content"));
        for (int i = 0; i < rows.size(); i++) {
            normalized.add(List.of(String.valueOf(i + 1),
                    String.join(" ", rows.get(i)).replaceAll("\s+", " ").strip()));
        }
        return normalized;
    }
    private int numericCellCount(List<String> row) {
        int count = 0;
        for (String cell : row) {
            if (NUMERIC_VALUE.matcher(normalizeTableToken(cell)).matches()
                    || isMissingValueMarker(normalizeTableToken(cell))) {
                count++;
            }
        }
        return count;
    }

    private int modalDataWidth(List<List<String>> rows, int firstDataRow) {
        Map<Integer, Integer> frequencies = new HashMap<>();
        for (int i = firstDataRow; i < rows.size(); i++) {
            if (numericCellCount(rows.get(i)) >= 2) {
                frequencies.merge(rows.get(i).size(), 1, Integer::sum);
            }
        }
        int width = -1;
        int frequency = -1;
        for (Map.Entry<Integer, Integer> entry : frequencies.entrySet()) {
            if (entry.getValue() > frequency
                    || (entry.getValue() == frequency && entry.getKey() > width)) {
                width = entry.getKey();
                frequency = entry.getValue();
            }
        }
        return width;
    }

    private List<String> collapseRepeatedHeaderSuffixes(List<String> header) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String cell : header) {
            String token = normalizeTableToken(cell).toLowerCase(Locale.ROOT);
            if (!token.isBlank() && !NUMERIC_VALUE.matcher(token).matches()) {
                frequencies.merge(token, 1, Integer::sum);
            }
        }

        List<String> collapsed = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i);
            String token = normalizeTableToken(cell).toLowerCase(Locale.ROOT);
            if (i > 0 && frequencies.getOrDefault(token, 0) >= 2 && !collapsed.isEmpty()) {
                int previous = collapsed.size() - 1;
                collapsed.set(previous, collapsed.get(previous) + " " + cell);
            } else {
                collapsed.add(cell);
            }
        }
        return collapsed;
    }

    private int findSharedUnitBoundary(List<List<String>> rows, int firstDataRow, int width) {
        for (int boundary = width - 2; boundary >= 0; boundary--) {
            String sharedRight = null;
            int supportingRows = 0;
            boolean compatible = true;
            for (int i = firstDataRow; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.size() != width || numericCellCount(row) < 2) continue;
                String left = normalizeTableToken(row.get(boundary));
                String right = normalizeTableToken(row.get(boundary + 1)).toLowerCase(Locale.ROOT);
                if (!NUMERIC_VALUE.matcher(left).matches() || right.isBlank()
                        || NUMERIC_VALUE.matcher(right).matches()) {
                    compatible = false;
                    break;
                }
                if (sharedRight == null) sharedRight = right;
                else if (!sharedRight.equals(right)) {
                    compatible = false;
                    break;
                }
                supportingRows++;
            }
            if (compatible && supportingRows >= 2) return boundary;
        }
        return -1;
    }

    private void mergeCells(List<String> row, int leftIndex) {
        row.set(leftIndex, (row.get(leftIndex) + " " + row.get(leftIndex + 1))
                .replaceAll("\s+", " ").strip());
        row.remove(leftIndex + 1);
    }
    private String extractTableCaption(String line) {
        if (line == null) {
            return null;
        }
        String stripped = line.strip();
        if (stripped.length() < 8 || stripped.length() > 300) {
            return null;
        }
        if (stripped.matches("(?i)^table\\s+[ivxlcdm0-9]+\\s*[:.\\-]\\s*.+")) {
            return stripped;
        }
        return null;
    }

    private String extractFigureCaption(String line) {
        if (line == null) {
            return null;
        }
        String stripped = line.strip();
        if (stripped.length() < 8 || stripped.length() > 300) {
            return null;
        }
        if (stripped.matches("(?i)^(figure|fig\\.)\\s+[ivxlcdm0-9]+\\s*[:.\\-]\\s*.+")) {
            return stripped;
        }
        return null;
    }

    private List<String> parseTableRow(String line, boolean tableContext, boolean allowCompactHeader) {
        List<String> cells = new ArrayList<>();
        String stripped = line == null ? "" : line.strip();
        if (stripped.length() < 8 || sectionDetector.detect(stripped).hasHeading()
                || isNonTableBoundary(stripped)) {
            return cells;
        }
        if (stripped.startsWith("|") && stripped.endsWith("|") && stripped.indexOf('|', 1) > 0) {
            for (String cell : stripped.split("\\|")) {
                addCell(cells, cell);
            }
            return cells;
        }
        if (!hasTableSignal(stripped)) {
            return inferWhitespaceTableRow(stripped, tableContext, allowCompactHeader);
        }
        for (String cell : stripped.split("\\t+| {2,}")) {
            addCell(cells, cell);
        }
        return cells;
    }

    /**
     * PDFBox collapses both word boundaries and table-column gaps to single spaces. Recover only
     * characteristic academic-table rows to avoid treating ordinary prose as a table.
     */
    private List<String> inferWhitespaceTableRow(String line, boolean tableContext, boolean allowCompactHeader) {
        String[] tokens = line.split("\s+");
        if (tokens.length < 2 || !tableContext) return List.of();

        int suffixStart = tokens.length;
        int numericCount = 0;
        int valueCount = 0;
        for (int i = tokens.length - 1; i >= 0; i--) {
            String token = normalizeTableToken(tokens[i]);
            if (NUMERIC_VALUE.matcher(token).matches()) {
                numericCount++;
                valueCount++;
                suffixStart = i;
            } else if (isMissingValueMarker(token)) {
                valueCount++;
                suffixStart = i;
            } else {
                break;
            }
        }
        if (numericCount >= 2 && valueCount >= 2 && suffixStart > 0) {
            List<String> inferred = new ArrayList<>();
            inferred.add(String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, suffixStart)).strip());
            for (int i = suffixStart; i < tokens.length; i++) addCell(inferred, tokens[i]);
            return inferred;
        }

        if (!allowCompactHeader) return List.of();

        boolean compactHeader = tokens.length <= 24
                && !line.matches(".*[.!?;:]$")
                && java.util.Arrays.stream(tokens).allMatch(token -> token.length() <= 40);
        return compactHeader ? nonBlankTokens(tokens) : List.of();
    }
    private List<String> nonBlankTokens(String[] tokens) {
        List<String> cells = new ArrayList<>();
        for (String token : tokens) addCell(cells, token);
        return cells;
    }

    private String normalizeTableToken(String token) {
        if (token == null) return "";
        return token.strip().replaceAll("^[|,;:()]+|[|,;:()]+$", "");
    }

    private boolean isMissingValueMarker(String token) {
        return "-".equals(token) || "\u2013".equals(token) || "\u2014".equals(token)
                || "N/A".equalsIgnoreCase(token);
    }

    private boolean isNonTableBoundary(String line) {
        if (line == null) return false;
        String value = line.strip();
        boolean shortHeading = value.length() <= 100
                && value.matches("^(?:[A-Z]\\.\\d+|\\d+(?:\\.\\d+){0,2})\\s+[A-Z][A-Za-z]+(?:\\s+[A-Z][A-Za-z]+){0,6}$");
        boolean longFootnote = value.length() >= 80 && value.matches("^\\d+\\s+.*");
        return shortHeading || longFootnote;
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

    /**
     * PDFBox normally writes one ordinary space for both word gaps and table-column gaps. This
     * variant reconstructs wider gaps from glyph coordinates, which lets the existing table parser
     * distinguish columns without an external parser.
     */
    private static final class LayoutPreservingTextStripperByArea extends PDFTextStripperByArea {
        private TextPosition previousPosition;

        private LayoutPreservingTextStripperByArea() throws IOException {
            super();
        }
        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty()) {
                super.writeString(text, positions);
                return;
            }

            StringBuilder reconstructed = new StringBuilder();

            for (TextPosition position : positions) {
                String unicode = normalizeSymbolGlyph(position.getFont().getName(), position.getUnicode());
                if (unicode == null || unicode.isEmpty()) continue;

                if (previousPosition != null && !endsWithWhitespace(reconstructed)
                        && !Character.isWhitespace(unicode.codePointAt(0))) {
                    float previousEnd = previousPosition.getXDirAdj() + previousPosition.getWidthDirAdj();
                    float gap = position.getXDirAdj() - previousEnd;
                    float spaceWidth = Math.max(previousPosition.getWidthOfSpace(), position.getWidthOfSpace());
                    if (!Float.isFinite(spaceWidth) || spaceWidth <= 0) {
                        spaceWidth = Math.max(1f, position.getFontSizeInPt() * 0.25f);
                    }
                    if (gap > spaceWidth * 5.0f) {
                        int spaces = Math.max(2, Math.min(24, Math.round(gap / spaceWidth)));
                        reconstructed.append(" ".repeat(spaces));
                    } else if (gap > spaceWidth * 0.18f) {
                        reconstructed.append(' ');
                    }
                }

                reconstructed.append(unicode);
                previousPosition = position;
            }
            super.writeString(reconstructed.toString(), positions);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            previousPosition = null;
            super.writeLineSeparator();
        }

        private static String normalizeSymbolGlyph(String fontName, String unicode) {
            if (unicode == null || unicode.isEmpty() || fontName == null) return unicode;
            String normalizedFont = fontName.toLowerCase(Locale.ROOT);
            if (normalizedFont.contains("bbding")) {
                if ("!".equals(unicode)) return "✓";
                if ("%".equals(unicode)) return "✗";
            }
            return unicode;
        }
        private boolean endsWithWhitespace(StringBuilder value) {
            return !value.isEmpty() && Character.isWhitespace(value.charAt(value.length() - 1));
        }
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
                .replaceAll("(?<=[A-Z]{2})-[ \\t]*\\n[ \\t]*(?=[A-Z]{2}\\b)", "-")
                .replaceAll("(?<=[A-Za-z])-[ \\t]*\\n[ \\t]*(?=[a-z])", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private record CaptionContinuation(String bodyPart, String captionPart) {}

    private record PageSection(String title, String sectionType, String text) {}

    private String extractTitle(String firstPageText) {
        if (firstPageText == null || firstPageText.isBlank()) {
            return null;
        }
        String[] lines = firstPageText.split("\\R");
        List<String> candidates = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.replaceAll("\\s+", " ").strip();
            if (line.isBlank()) {
                if (!candidates.isEmpty()) {
                    break;
                }
                continue;
            }
            if (line.equals("[TABLE]") || line.equals("[/TABLE]") || line.startsWith("|")
                    || line.startsWith("[FIGURE_CAPTION]") || line.startsWith("[/FIGURE_CAPTION]")) {
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith("abstract")) {
                break;
            }
            if (line.startsWith(TABLE_CAPTION_START) || line.startsWith("[FIGURE_CAPTION]")) {
                continue;
            }
            if (sectionDetector.detect(line).sectionType() != null) {
                break;
            }
            if (looksLikeAuthorOrMetadata(line)) {
                if (!candidates.isEmpty()) {
                    break;
                }
                continue;
            }
            if (looksLikeTitleLine(line)) {
                candidates.add(line);
                if (candidates.size() >= 3) {
                    break;
                }
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
                || lower.matches(".*\\b20\\d{2}\\b.*")
                || looksLikeAuthorLine(line);
    }

    private boolean looksLikeAuthorLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.strip();
        if (trimmed.length() < 6 || trimmed.length() > 260 || !trimmed.contains(",")) {
            return false;
        }
        return trimmed.matches("(?s).*(\\b[A-Z][a-z]+\\d?\\b\\s*,\\s*){1,}\\b[A-Z][a-z]+\\d?\\b.*")
                || trimmed.matches("(?s).*(\\b[A-Z][a-z]+\\d?\\b.*\\b[A-Z][a-z]+\\d?\\b).*");
    }

    private boolean looksLikeTitleLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.strip();
        if (trimmed.length() < 6 || trimmed.length() > 180) {
            return false;
        }
        return !trimmed.contains("@") && !trimmed.matches(".*\\b20\\d{2}\\b.*");
    }
}
