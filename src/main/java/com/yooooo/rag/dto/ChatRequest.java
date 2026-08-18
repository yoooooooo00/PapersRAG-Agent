package com.yooooo.rag.dto;

import java.util.List;
import lombok.Data;

/**
 * 聊天接口请求参数，包含问题、知识库范围和会话标识。
 */
@Data
public class ChatRequest {
    private String question;
    private List<Long> kbIds;
    private String sessionId;
}
