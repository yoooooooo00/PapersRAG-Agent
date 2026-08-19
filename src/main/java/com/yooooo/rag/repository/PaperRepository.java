package com.yooooo.rag.repository;

import com.yooooo.rag.entity.Paper;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperRepository extends JpaRepository<Paper, Long> {
    List<Paper> findByIsDeletedFalseOrderByUpdatedAtDesc();

    List<Paper> findByKbIdAndIsDeletedFalseOrderByUpdatedAtDesc(Long kbId);

    List<Paper> findByReadingStatusAndIsDeletedFalseOrderByUpdatedAtDesc(Paper.ReadingStatus readingStatus);

    List<Paper> findByTitleContainingIgnoreCaseAndIsDeletedFalseOrderByUpdatedAtDesc(String title);
}