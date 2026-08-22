package com.yooooo.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Evaluation task record.
 */
@Entity
@Table(name = "kb_eval_task")
@Data
public class EvalTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "eval_version", nullable = false, length = 50)
    private String evalVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "total_questions", nullable = false)
    private Integer totalQuestions = 0;

    @Column(name = "processed_questions", nullable = false)
    private Integer processedQuestions = 0;

    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;

    @Column(name = "hit_rate")
    private Double hitRate;

    @Column(name = "mrr")
    private Double mrr;

    @Column(name = "avg_faithfulness")
    private Double avgFaithfulness;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public enum TaskStatus {
        PENDING, RUNNING, DONE, FAILED
    }
}
