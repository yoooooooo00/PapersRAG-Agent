package com.yooooo.rag.service.loader;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 文档解析结果对象，包含解析是否成功、页数和文本段落。
 */
@Data
@Builder
public class ParseResult {
    private boolean success;
    private String errorMsg;
    private List<PageContent> pages;
    private int totalPages;
    private String title;
/**
 * 文档单页解析结果，包含页码和文本。
 */

    @Data
    @Builder
    public static class PageContent {
        private int pageNum;
        private String text;
        private String sectionTitle;
    }

    public static ParseResult failure(String errorMsg) {
        return ParseResult.builder()
                .success(false)
                .errorMsg(errorMsg)
                .pages(List.of())
                .build();
    }

    public String getFullText() {
        if (pages == null) return "";
        return pages.stream()
                .map(PageContent::getText)
                .filter(t -> t != null && !t.isBlank())
                .reduce("", (a, b) -> a + "\n\n" + b)
                .strip();
    }
}
