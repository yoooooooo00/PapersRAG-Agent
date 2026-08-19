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

@Entity
@Table(name = "paper_relation")
@Data
public class PaperRelation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_paper_id", nullable = false)
    private Long sourcePaperId;

    @Column(name = "target_paper_id", nullable = false)
    private Long targetPaperId;

    @Column(name = "relation_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RelationType relationType;

    @Column(name = "evidence_chunk_id")
    private Long evidenceChunkId;

    @Column(columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    public enum RelationType {
        CITES, EXTENDS, COMPARES_WITH, USES_METHOD, USES_DATASET, CONTRADICTS, SAME_TOPIC
    }
}