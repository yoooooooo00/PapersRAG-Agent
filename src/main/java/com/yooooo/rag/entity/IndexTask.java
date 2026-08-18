package com.yooooo.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 索引任务实体，记录文档索引或重建索引的执行状态。
 */
@Entity
@Table(name = "kb_index_task")
@Data
public class IndexTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "task_type", nullable = false, length = 20)
    private String taskType = "INDEX";

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retry", nullable = false)
    private Integer maxRetry = 3;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
/**
 * 索引任务执行状态。
 */

    public enum TaskStatus {
        PENDING, RUNNING, DONE, FAILED
    }

    public boolean canRetry() {
        return retryCount < maxRetry && status == TaskStatus.FAILED;
    }
}
