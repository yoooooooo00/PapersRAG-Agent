package com.yooooo.rag.repository;

import com.yooooo.rag.entity.ChatSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 聊天会话的数据访问接口。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findByUserIdAndIsDeletedFalseOrderByLastActiveAtDesc(Long userId);
}
