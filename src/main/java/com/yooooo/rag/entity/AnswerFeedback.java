package com.yooooo.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 回答反馈实体，记录用户对某条回答的点赞、点踩和评论。
 */
@Entity
@Table(name = "kb_answer_feedback")
@Data
public class AnswerFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long messageId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Short feedback;

    private String comment;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
