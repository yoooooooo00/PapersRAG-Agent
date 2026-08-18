package com.yooooo.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 聊天会话实体，记录用户一次连续问答的会话信息。
 */
@Entity
@Table(name = "kb_chat_session")
@Data
public class ChatSession {
    @Id
    private String id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String kbIds;

    private String title;

    @Column(nullable = false)
    private Integer messageCount = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastActiveAt = LocalDateTime.now();

    @Column(nullable = false)
    private Boolean isDeleted = false;
}
