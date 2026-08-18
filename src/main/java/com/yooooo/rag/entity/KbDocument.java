package com.yooooo.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 知识库文档实体，保存文件元数据、存储路径和索引状态。
 */
@Entity
@Table(name = "kb_document")
@Data
public class KbDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "minio_path", nullable = false, length = 500)
    private String minioPath;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    @Column(name = "token_count")
    private Integer tokenCount = 0;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;
/**
 * 文档索引处理状态。
 */

    public enum DocumentStatus {
        PENDING, PROCESSING, DONE, FAILED
    }
}
