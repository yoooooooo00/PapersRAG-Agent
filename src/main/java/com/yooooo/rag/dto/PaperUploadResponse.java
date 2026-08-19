package com.yooooo.rag.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperUploadResponse {
    private Long paperId;
    private Long docId;
    private Long kbId;
    private String title;
    private String fileName;
    private String documentStatus;
    private String message;
}