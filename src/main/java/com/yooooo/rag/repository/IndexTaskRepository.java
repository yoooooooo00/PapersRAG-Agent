package com.yooooo.rag.repository;

import com.yooooo.rag.entity.IndexTask;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 索引任务的数据访问接口，用于查询待执行任务和最新任务状态。
 */
public interface IndexTaskRepository extends JpaRepository<IndexTask, Long> {
    Optional<IndexTask> findTopByDocIdOrderByCreatedAtDesc(Long docId);
}
