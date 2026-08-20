package com.yooooo.rag.service.splitter;

import lombok.Builder;
import lombok.Data;

/**
 * Result of splitting parsed content into searchable chunks.
 */
@Data
@Builder
public class ChunkResult {
    private int chunkIndex;

    private String content;

    private String rawContent;

    private String tableCaption;

    private Integer pageNum;

    private String sectionTitle;

    private String sectionType;

    private String contentType;

    private int estimatedTokens;
}
