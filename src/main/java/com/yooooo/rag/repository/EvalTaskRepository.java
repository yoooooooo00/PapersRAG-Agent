package com.yooooo.rag.repository;

import com.yooooo.rag.entity.EvalTask;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Evaluation task repository.
 */
public interface EvalTaskRepository extends JpaRepository<EvalTask, Long> {
    List<EvalTask> findByKbIdOrderByCreatedAtDesc(Long kbId);
}
