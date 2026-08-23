package com.yooooo.rag.service.loader;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 鏂囨。瑙ｆ瀽缁撴灉瀵硅薄锛屽寘鍚В鏋愭槸鍚︽垚鍔熴€侀〉鏁板拰鏂囨湰娈佃惤銆? */
@Data
@Builder
public class ParseResult {
    private boolean success;
    private String errorMsg;
    private List<PageContent> pages;
    private int totalPages;
    private String title;
    private PaperMetadata paperMetadata;
/**
 * 鏂囨。鍗曢〉瑙ｆ瀽缁撴灉锛屽寘鍚〉鐮佸拰鏂囨湰銆? */

    @Data
    @Builder
    public static class PageContent {
        private int pageNum;
        private String text;
        private String sectionTitle;
        private String sectionType;
        private String contentType;
    }

    @Data
    @Builder
    public static class PaperMetadata {
        private String title;
        private String authors;
        private String affiliations;
        private Integer publicationYear;
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
