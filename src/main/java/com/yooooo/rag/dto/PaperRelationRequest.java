package com.yooooo.rag.dto;

import lombok.Data;

@Data
public class PaperRelationRequest {
    private Long targetPaperId;
    private String relationType;
    private Long evidenceChunkId;
    private String note;
}