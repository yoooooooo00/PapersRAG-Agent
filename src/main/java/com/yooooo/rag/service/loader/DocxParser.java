package com.yooooo.rag.service.loader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

/**
 * 解析 DOCX 文档，提取段落文本和基础结构信息。
 */
@Component
@Slf4j
public class DocxParser implements DocumentParser {
    @Override
    public String supportedType() {
        return "DOCX";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            List<ParseResult.PageContent> pages = new ArrayList<>();
            StringBuilder currentSection = new StringBuilder();
            String currentTitle = null;
            int sectionCount = 0;

            for (IBodyElement elem : document.getBodyElements()) {
                if (elem instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text == null || text.isBlank()) continue;

                    String style = paragraph.getStyle();
                    boolean isHeading = style != null &&
                            (style.startsWith("Heading") || style.startsWith("heading") ||
                                    style.contains("标题"));

                    if (isHeading && currentSection.length() > 200) {
                        pages.add(ParseResult.PageContent.builder()
                                .pageNum(++sectionCount)
                                .text(currentSection.toString().strip())
                                .sectionTitle(currentTitle)
                                .build());
                        currentSection = new StringBuilder();
                        currentTitle = text;
                    } else if (isHeading) {
                        currentTitle = text;
                    }

                    currentSection.append(text).append("\n");

                } else if (elem instanceof XWPFTable table) {
                    StringBuilder tableText = new StringBuilder();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cellTexts = row.getTableCells().stream()
                                .map(XWPFTableCell::getText)
                                .filter(t -> !t.isBlank())
                                .toList();
                        if (!cellTexts.isEmpty()) {
                            tableText.append(String.join(" | ", cellTexts)).append("\n");
                        }
                    }
                    if (!tableText.isEmpty()) {
                        currentSection.append("\n[表格]\n").append(tableText);
                    }
                }
            }

            if (!currentSection.isEmpty()) {
                pages.add(ParseResult.PageContent.builder()
                        .pageNum(++sectionCount)
                        .text(currentSection.toString().strip())
                        .sectionTitle(currentTitle)
                        .build());
            }

            if (pages.isEmpty()) {
                return ParseResult.failure("Word 文档内容为空");
            }

            String title = null;
            try (XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                title = extractor.getCoreProperties().getTitle();
            } catch (Exception ignored) {
            }

            log.info("[DOCX解析] 文件={}，段落分节={}节", fileName, pages.size());

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(pages.size())
                    .title(title)
                    .build();

        } catch (Exception e) {
            log.error("[DOCX解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("Word 文档解析失败：" + e.getMessage());
        }
    }
}
