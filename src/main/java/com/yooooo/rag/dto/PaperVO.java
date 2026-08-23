package com.yooooo.rag.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperVO {
    private Long id;
    private Long kbId;
    private Long docId;
    private String title;
    private String authors;
    private String affiliations;
    private Integer year;
    private String venue;
    private String doi;
    private String arxivId;
    private String abstractText;
    private String keywords;
    private String bibtex;
    private String sourceUrl;
    private String pdfUrl;
    private String readingStatus;
    private Integer rating;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}