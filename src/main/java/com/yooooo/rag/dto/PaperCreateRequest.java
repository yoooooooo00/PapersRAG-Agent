package com.yooooo.rag.dto;

import lombok.Data;

@Data
public class PaperCreateRequest {
    private Long kbId = 1L;
    private Long docId;
    private String title;
    private String authors;
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
}