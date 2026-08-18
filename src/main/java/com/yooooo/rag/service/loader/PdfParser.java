package com.yooooo.rag.service.loader;

import java.awt.geom.Rectangle2D;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 解析 PDF 文档，按页面提取文本内容。
 */
@Component
@Slf4j
public class PdfParser implements DocumentParser {
    @Value("${rag.parser.pdf.crop-header-points:60}")
    private float cropHeaderPoints;

    @Value("${rag.parser.pdf.crop-footer-points:50}")
    private float cropFooterPoints;

    private static final Pattern HEADING_PATTERN =
            Pattern.compile("^(第[一二三四五六七八九十百\\d]+[章节]|[一二三四五六七八九十]+、|\\d+\\.)\\s*.+");

    @Override
    public String supportedType() {
        return "PDF";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            int totalPages = document.getNumberOfPages();
            List<ParseResult.PageContent> pages = new ArrayList<>();

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    String text = extractBodyText(document.getPage(pageNum - 1));
                    text = cleanText(text);

                    if (text.isBlank()) {
                        log.debug("[PDF解析] 第{}页内容为空，可能是图片页，跳过", pageNum);
                        continue;
                    }

                    pages.add(ParseResult.PageContent.builder()
                            .pageNum(pageNum)
                            .text(text)
                            .sectionTitle(detectHeading(text))
                            .build());

                } catch (Exception e) {
                    log.warn("[PDF解析] 第{}页解析失败：{}", pageNum, e.getMessage());
                }
            }

            if (pages.isEmpty()) {
                return ParseResult.failure("PDF 解析后无有效文本内容，可能是纯图片 PDF，需要 OCR 处理");
            }

            log.info("[PDF解析] 文件={}，总页数={}，有效页数={}", fileName, totalPages, pages.size());

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(totalPages)
                    .title(extractTitle(pages))
                    .build();

        } catch (Exception e) {
            log.error("[PDF解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("PDF 解析失败：" + e.getMessage());
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

    private String detectHeading(String text) {
        String[] lines = text.split("\n");
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            String line = lines[i].strip();
            if (line.length() > 2 && line.length() < 50) {
                Matcher m = HEADING_PATTERN.matcher(line);
                if (m.matches()) return line;
            }
        }
        return null;
    }

    private String extractTitle(List<ParseResult.PageContent> pages) {
        if (pages.isEmpty()) return null;
        String firstPageText = pages.get(0).getText();
        String[] lines = firstPageText.split("\n");
        for (String line : lines) {
            line = line.strip();
            if (!line.isBlank() && line.length() < 100) {
                return line;
            }
        }
        return null;
    }
}
