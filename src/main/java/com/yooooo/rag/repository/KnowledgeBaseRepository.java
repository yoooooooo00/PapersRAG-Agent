package com.yooooo.rag.repository;

import com.yooooo.rag.entity.KnowledgeBase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for knowledge bases.
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    List<KnowledgeBase> findByIsDeletedFalse();

    Optional<KnowledgeBase> findFirstByIsDeletedFalseOrderByIdAsc();

    List<KnowledgeBase> findByDepartmentIdAndIsDeletedFalse(String departmentId);

    List<KnowledgeBase> findByIsPublicTrueAndIsDeletedFalse();
}
