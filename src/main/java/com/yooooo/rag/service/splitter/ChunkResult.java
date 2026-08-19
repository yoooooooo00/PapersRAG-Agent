package com.yooooo.rag.service.splitter;

import lombok.Builder;
import lombok.Data;

/**
 * 文档切分结果，保存切分后的文本块及其元信息。
 */
@Data
@Builder
public class ChunkResult {
    private int chunkIndex;

    private String content;

    private Integer pageNum;

    private String sectionTitle;

    private String sectionType;

    private String contentType;

    private int estimatedTokens;
}
