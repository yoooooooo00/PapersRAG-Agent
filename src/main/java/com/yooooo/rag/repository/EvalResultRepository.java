package com.yooooo.rag.repository;

import com.yooooo.rag.dto.EvalReport;
import com.yooooo.rag.entity.EvalResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Evaluation result repository.
 */
public interface EvalResultRepository extends JpaRepository<EvalResult, Long> {
    List<EvalResult> findByTaskIdOrderByEvalAtDesc(Long taskId);

    List<EvalResult> findByDatasetIdInAndEvalVersionOrderByEvalAtDesc(List<Long> datasetIds, String evalVersion);

    @Query("""
            SELECT new com.yooooo.rag.dto.EvalReport(
                d.kbId, r.evalVersion, COUNT(r),
                SUM(CASE WHEN r.hit = true THEN 1 ELSE 0 END),
                AVG(CASE WHEN r.hit = true THEN 1.0 ELSE 0.0 END),
                AVG(CASE WHEN r.rank > 0 THEN 1.0 / r.rank ELSE 0.0 END),
                AVG(COALESCE(r.faithfulness, 0)),
                MAX(r.evalAt))
            FROM EvalResult r JOIN EvalDataset d ON r.datasetId = d.id
            WHERE d.kbId = :kbId
            GROUP BY d.kbId, r.evalVersion
            ORDER BY MAX(r.evalAt) DESC
            """)
    List<EvalReport> aggregateByVersion(Long kbId);
}
