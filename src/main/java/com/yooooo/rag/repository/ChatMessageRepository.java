package com.yooooo.rag.repository;

import com.yooooo.rag.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 聊天消息的数据访问接口。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
