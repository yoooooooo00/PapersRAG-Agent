package com.yooooo.rag.repository;

import com.yooooo.rag.entity.Paper;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperRepository extends JpaRepository<Paper, Long> {
    Optional<Paper> findFirstByDocIdAndIsDeletedFalse(Long docId);
    List<Paper> findByIsDeletedFalseOrderByUpdatedAtDesc();

    List<Paper> findByKbIdAndIsDeletedFalseOrderByUpdatedAtDesc(Long kbId);

    List<Paper> findByReadingStatusAndIsDeletedFalseOrderByUpdatedAtDesc(Paper.ReadingStatus readingStatus);

    List<Paper> findByTitleContainingIgnoreCaseAndIsDeletedFalseOrderByUpdatedAtDesc(String title);
}