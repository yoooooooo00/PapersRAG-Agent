package com.yooooo.rag.repository;

import com.yooooo.rag.entity.EvalDataset;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评估数据集的数据访问接口。
 */
public interface EvalDatasetRepository extends JpaRepository<EvalDataset, Long> {
    List<EvalDataset> findByKbId(Long kbId);
}
