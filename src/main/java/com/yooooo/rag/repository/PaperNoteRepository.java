package com.yooooo.rag.repository;

import com.yooooo.rag.entity.PaperNote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperNoteRepository extends JpaRepository<PaperNote, Long> {
    List<PaperNote> findByPaperIdAndIsDeletedFalseOrderByUpdatedAtDesc(Long paperId);
}