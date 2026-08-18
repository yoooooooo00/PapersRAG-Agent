package com.yooooo.rag.dto;

import lombok.Data;

/**
 * 创建知识库时的请求参数。
 */
@Data
public class KnowledgeBaseCreateRequest {
    private String name;
    private String description;
    private String departmentId;
    private Boolean isPublic = false;
}
