package com.yooooo.rag.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperNoteVO {
    private Long id;
    private Long paperId;
    private String noteType;
    private String content;
    private Integer pageNum;
    private String sectionTitle;
    private Long linkedChunkId;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}