package com.yooooo.rag.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 问答响应对象，包含答案、引用来源、耗时和未命中标记。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse {
    private String answer;
    private List<Source> sources;
    private int latencyMs;
    private boolean notFound;
/**
 * 答案引用来源，描述命中的文档分块和相关分数。
 */

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private Long chunkId;
        private Long docId;
        private Integer pageNum;
        private String sectionTitle;
        private String excerpt;
        private double score;
        private String docName;
    }

    public static RagResponse notFound() {
        return RagResponse.builder()
                .answer("在知识库中未找到与该问题相关的内容。建议您：\n" +
                        "1. 确认问题是否属于该知识库的覆盖范围\n" +
                        "2. 尝试更换关键词提问\n" +
                        "3. 联系相关部门获取准确信息")
                .sources(List.of())
                .notFound(true)
                .build();
    }
}
