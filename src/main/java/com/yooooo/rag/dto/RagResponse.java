package com.yooooo.rag.dto;

import com.yooooo.rag.service.retrieval.QueryRoutingService;
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
    private QueryRoutingService.QueryRoute queryRoute;
    private Long[] retrievedChunkIds;
    private Long[] trimmedChunkIds;

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
        return notFound(null);
    }

    public static RagResponse notFound(QueryRoutingService.QueryRoute route) {
        return RagResponse.builder()
                .answer("No relevant content was found in the knowledge base. Try a more specific query.")
                .sources(List.of())
                .retrievedChunkIds(new Long[0])
                .trimmedChunkIds(new Long[0])
                .queryRoute(route)
                .notFound(true)
                .build();
    }
}
