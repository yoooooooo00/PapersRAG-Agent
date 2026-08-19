package com.yooooo.rag.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperRelationVO {
    private Long id;
    private Long sourcePaperId;
    private Long targetPaperId;
    private String relationType;
    private Long evidenceChunkId;
    private String note;
    private LocalDateTime createdAt;
}