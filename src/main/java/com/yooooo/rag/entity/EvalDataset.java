package com.yooooo.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 评估数据集实体，保存标准问题、期望答案和期望命中文档块。
 */
@Entity
@Table(name = "kb_eval_dataset")
@Data
public class EvalDataset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long kbId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String expectedAnswer;

    @Column(columnDefinition = "BIGINT[]")
    private Long[] expectedChunkIds;

    @Column(nullable = false)
    private Long createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
