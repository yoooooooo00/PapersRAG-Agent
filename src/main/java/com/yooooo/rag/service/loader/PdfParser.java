package com.yooooo.rag.service.loader;

import java.awt.geom.Rectangle2D;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
 * Parses academic PDF files into page text with lightweight section metadata.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PdfParser implements DocumentParser {
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
            List<ParseResult.PageContent> pages = new ArrayList<>();
            String currentSectionTitle = null;
            String currentSectionType = null;

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    String text = cleanText(extractBodyText(document.getPage(pageNum - 1)));
                    if (text.isBlank()) {
                        log.debug("[PdfParser] Empty text page skipped fileName={} page={}", fileName, pageNum);
                        continue;
                    }

                    AcademicSectionDetector.SectionMatch match = sectionDetector.detectFromText(text);
                    if (match.sectionType() != null) {
                        currentSectionTitle = match.sectionTitle();
                        currentSectionType = match.sectionType();
                    }

                    pages.add(ParseResult.PageContent.builder()
                            .pageNum(pageNum)
                            .text(text)
                            .sectionTitle(currentSectionTitle)
                            .sectionType(currentSectionType)
                            .build());
                } catch (Exception e) {
                    log.warn("[PdfParser] Failed to parse page fileName={} page={} reason={}",
                            fileName, pageNum, e.getMessage());
                }
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

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
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
        String lower = line.toLowerCase();
        return lower.contains("@")
                || lower.contains("university")
                || lower.contains("institute")
                || lower.contains("department")
                || lower.contains("arxiv")
                || lower.startsWith("http")
                || lower.matches(".*\\b20\\d{2}\\b.*");
    }
}