package com.yooooo.rag.repository;

import com.yooooo.rag.entity.AnswerFeedback;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 回答反馈的数据访问接口。
 */
public interface AnswerFeedbackRepository extends JpaRepository<AnswerFeedback, Long> {
    Optional<AnswerFeedback> findByMessageIdAndUserId(Long messageId, Long userId);
}
