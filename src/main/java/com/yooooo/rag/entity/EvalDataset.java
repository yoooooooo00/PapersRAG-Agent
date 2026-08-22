package com.yooooo.rag.entity;

import com.yooooo.rag.service.retrieval.QueryRoutingService;
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
 * Evaluation dataset entry.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_route", length = 20)
    private QueryRoutingService.QueryRoute expectedRoute;

    @Column(nullable = false)
    private Long createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
