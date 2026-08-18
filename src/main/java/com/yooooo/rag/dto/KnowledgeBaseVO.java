package com.yooooo.rag.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * 知识库列表展示对象，附带当前用户权限信息。
 */
@Data
@Builder
public class KnowledgeBaseVO {
    private Long id;
    private String name;
    private String description;
    private String departmentId;
    private Boolean isPublic;
    private Long createdBy;
    private LocalDateTime createdAt;

    private String permission;
}
