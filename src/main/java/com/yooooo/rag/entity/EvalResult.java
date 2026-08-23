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

/**
 * Evaluation result entry.
 */
@Entity
@Table(name = "kb_eval_result")
@Data
public class EvalResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long datasetId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(nullable = false, length = 50)
    private String evalVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_route", length = 20)
    private QueryRoutingService.QueryRoute expectedRoute;

    @Enumerated(EnumType.STRING)
    @Column(name = "query_route", length = 20)
    private QueryRoutingService.QueryRoute queryRoute;

    @Column(nullable = false)
    private Boolean hit;

    private Integer rank;

    @Column(columnDefinition = "BIGINT[]")
    private Long[] retrievedChunkIds;

    @Column(columnDefinition = "BIGINT[]")
    private Long[] usedChunkIds;

    @Column(columnDefinition = "TEXT")
    private String actualAnswer;

    @Column(columnDefinition = "TEXT")
    private String routeTrace;

    @Column(columnDefinition = "TEXT")
    private String retrievalTrace;

    @Column(columnDefinition = "TEXT")
    private String finalTrace;

    private Double faithfulness;

    @Column(nullable = false)
    private LocalDateTime evalAt = LocalDateTime.now();
}
