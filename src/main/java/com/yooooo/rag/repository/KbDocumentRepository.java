package com.yooooo.rag.repository;

import com.yooooo.rag.entity.KbDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 知识库文档的数据访问接口。
 */
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
    List<KbDocument> findByKbIdAndIsDeletedFalse(Long kbId);

    @Query("SELECT COUNT(d) FROM KbDocument d WHERE d.status = :status")
    long countByStatus(KbDocument.DocumentStatus status);
}
