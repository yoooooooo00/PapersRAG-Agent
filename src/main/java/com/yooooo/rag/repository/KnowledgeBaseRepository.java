package com.yooooo.rag.repository;

import com.yooooo.rag.entity.KnowledgeBase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库的数据访问接口。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    List<KnowledgeBase> findByIsDeletedFalse();

    List<KnowledgeBase> findByDepartmentIdAndIsDeletedFalse(String departmentId);

    List<KnowledgeBase> findByIsPublicTrueAndIsDeletedFalse();
}
