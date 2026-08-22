package com.yooooo.rag.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG answer response payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse {
    private String answer;
    private List<Source> sources;
    private int latencyMs;
    private boolean notFound;

    /**
     * Source item for a cited chunk.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private Long chunkId;
        private Long docId;
        private Integer pageNum;
        private String sectionTitle;
        private String excerpt;
        private double score;
        private String docName;
        private String contentType;
        private String tableCaption;
    }

    public static RagResponse notFound() {
        return RagResponse.builder()
                .answer("当前知识库中没有找到与该问题相关的可靠内容。建议你换个关键词，或者把问题问得更具体一点。")
                .sources(List.of())
                .notFound(true)
                .build();
    }
}