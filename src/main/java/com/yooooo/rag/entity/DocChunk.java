package com.yooooo.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 文档分块实体，保存切分后的文本、向量和全文检索字段。
 */
@Entity
@Table(name = "kb_doc_chunk")
@Data
public class DocChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "paper_id")
    private Long paperId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    @Column(name = "embedding", columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(name = "page_num")
    private Integer pageNum;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "section_type", length = 50)
    private String sectionType;

    @Column(name = "content_type", length = 50)
    private String contentType;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount = 0;

    @Column(name = "doc_version", nullable = false)
    private Integer docVersion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
