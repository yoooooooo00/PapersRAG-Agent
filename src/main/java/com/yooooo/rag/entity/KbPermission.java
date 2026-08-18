package com.yooooo.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 知识库权限实体，描述部门、角色或用户对知识库的访问权限。
 */
@Entity
@Table(name = "kb_permission")
@Data
public class KbPermission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "subject_type", nullable = false, length = 20)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, length = 50)
    private String subjectId;

    @Column(name = "permission", nullable = false, length = 20)
    private String permission;

    @Column(name = "granted_by")
    private Long grantedBy;

    @CreationTimestamp
    @Column(name = "granted_at")
    private LocalDateTime grantedAt;
}
