package com.yooooo.rag.dto;

import lombok.Data;

@Data
public class PaperNoteRequest {
    private String noteType;
    private String content;
    private Integer pageNum;
    private String sectionTitle;
    private Long linkedChunkId;
    private String tags;
}