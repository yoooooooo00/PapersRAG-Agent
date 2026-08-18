package com.yooooo.rag.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评估报告数据对象，汇总命中率、平均排名和生成质量指标。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EvalReport {
    private Long kbId;
    private String evalVersion;
    private long totalQuestions;
    private long hitCount;
    private double hitRate;
    private double mrr;
    private double avgFaithfulness;
    private LocalDateTime evalAt;

    public String summary() {
        return String.format(
                "评估版本：%s | 问题数：%d | Hit Rate：%.1f%% | MRR：%.4f | Faithfulness：%.4f",
                evalVersion, totalQuestions,
                hitRate * 100, mrr, avgFaithfulness);
    }
}
