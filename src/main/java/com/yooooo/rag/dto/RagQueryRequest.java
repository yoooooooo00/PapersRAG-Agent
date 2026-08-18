package com.yooooo.rag.dto;

import java.util.List;
import lombok.Data;

/**
 * RAG 查询请求参数，包含用户问题和要检索的知识库范围。
 */
@Data
public class RagQueryRequest {
    private String question;
    private List<Long> kbIds;
}
