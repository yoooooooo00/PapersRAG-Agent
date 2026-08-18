package com.yooooo.rag.repository;

import com.yooooo.rag.entity.KbPermission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库权限的数据访问接口。
 */
public interface KbPermissionRepository extends JpaRepository<KbPermission, Long> {
    List<KbPermission> findBySubjectTypeAndSubjectId(String subjectType, String subjectId);

    boolean existsByKbIdAndSubjectTypeAndSubjectId(
            Long kbId, String subjectType, String subjectId);

    boolean existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(
            Long kbId, String subjectType, String subjectId, List<String> permissions);

    List<KbPermission> findByKbId(Long kbId);

    void deleteByKbId(Long kbId);
}
