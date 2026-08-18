package com.yooooo.rag.dto;

import lombok.Data;

/**
 * 文档上传后的响应对象，返回文档编号、文件名和处理状态。
 */
@Data
public class DocumentUploadResponse {
    private Long docId;
    private String fileName;
    private String status;
    private String message;

    public static DocumentUploadResponse submitted(Long docId, String fileName) {
        DocumentUploadResponse r = new DocumentUploadResponse();
        r.setDocId(docId);
        r.setFileName(fileName);
        r.setStatus("PENDING");
        r.setMessage("文档已上传，正在后台索引，请通过 /status 接口查询进度");
        return r;
    }
}
