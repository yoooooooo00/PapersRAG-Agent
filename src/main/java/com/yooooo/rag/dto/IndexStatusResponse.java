package com.yooooo.rag.dto;

import lombok.Data;

/**
 * 文档索引状态响应对象，用于展示索引进度和失败原因。
 */
@Data
public class IndexStatusResponse {
    private Long docId;
    private String fileName;
    private String status;
    private String errorMsg;
    private Integer chunkCount;
    private Integer tokenCount;
    private String indexedAt;
    private Integer retryCount;
}
