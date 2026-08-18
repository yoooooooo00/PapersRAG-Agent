package com.yooooo.rag.service.loader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 解析 Markdown 文档，保留标题结构并提取正文内容。
 */
@Component
@Slf4j
public class MarkdownParser implements DocumentParser {
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,3}\\s+(.+)");

    @Override
    public String supportedType() {
        return "MD";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try {
            String markdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            List<ParseResult.PageContent> pages = new ArrayList<>();

            String[] lines = markdown.split("\n");
            StringBuilder currentSection = new StringBuilder();
            String currentTitle = null;
            int sectionCount = 0;
            boolean inCodeBlock = false;

            for (String line : lines) {
                if (line.startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    currentSection.append(line).append("\n");
                    continue;
                }

                if (inCodeBlock) {
                    currentSection.append(line).append("\n");
                    continue;
                }

                Matcher m = HEADING_PATTERN.matcher(line);
                if (m.matches() && (line.startsWith("# ") || line.startsWith("## "))) {
                    if (currentSection.length() > 100) {
                        pages.add(ParseResult.PageContent.builder()
                                .pageNum(++sectionCount)
                                .text(stripMarkdownSyntax(currentSection.toString()))
                                .sectionTitle(currentTitle)
                                .build());
                        currentSection = new StringBuilder();
                    }
                    currentTitle = m.group(1);
                }
                currentSection.append(line).append("\n");
            }

            if (!currentSection.isEmpty()) {
                pages.add(ParseResult.PageContent.builder()
                        .pageNum(++sectionCount)
                        .text(stripMarkdownSyntax(currentSection.toString()))
                        .sectionTitle(currentTitle)
                        .build());
            }

            if (pages.isEmpty()) {
                pages.add(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text(stripMarkdownSyntax(markdown))
                        .build());
            }

            log.info("[MD解析] 文件={}，分节={}节", fileName, pages.size());

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(pages.size())
                    .build();

        } catch (Exception e) {
            log.error("[MD解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("Markdown 解析失败：" + e.getMessage());
        }
    }

    private String stripMarkdownSyntax(String markdown) {
        return markdown
                .replaceAll("```[\\s\\S]*?```", " [代码块] ")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("!\\[.*?\\]\\(.*?\\)", " [图片] ")
                .replaceAll("\\[([^\\]]+)\\]\\(.*?\\)", "$1")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("(?m)^[-*+]\\s+", "")
                .replaceAll("(?m)^\\d+\\.\\s+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
