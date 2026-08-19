package com.yooooo.rag.repository;

import com.yooooo.rag.entity.PaperRelation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperRelationRepository extends JpaRepository<PaperRelation, Long> {
    List<PaperRelation> findBySourcePaperIdAndIsDeletedFalseOrderByCreatedAtDesc(Long sourcePaperId);

    List<PaperRelation> findByTargetPaperIdAndIsDeletedFalseOrderByCreatedAtDesc(Long targetPaperId);
}