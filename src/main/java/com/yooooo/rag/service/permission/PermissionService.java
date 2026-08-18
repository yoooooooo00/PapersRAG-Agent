package com.yooooo.rag.service.permission;

import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.entity.KnowledgeBase;
import com.yooooo.rag.exception.BizException;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.repository.KbPermissionRepository;
import com.yooooo.rag.repository.KnowledgeBaseRepository;
import com.yooooo.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 集中处理知识库读写权限判断。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {
    private final KbPermissionRepository permissionRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KbDocumentRepository documentRepository;

    public void requireRead(Long kbId) {
        if (UserContext.isAdmin()) {
            log.debug("[权限] 管理员读权限通过 userId={} kbId={}", UserContext.getUserId(), kbId);
            return;
        }

        boolean isPublic = kbRepository.findById(kbId)
                .map(KnowledgeBase::getIsPublic)
                .orElse(false);
        if (isPublic) {
            log.debug("[权限] 公开知识库读权限通过 userId={} kbId={}", UserContext.getUserId(), kbId);
            return;
        }

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();
        boolean hasPermission = permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                kbId, "USER", userId)
                || permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                kbId, "DEPARTMENT", deptId);

        if (!hasPermission) {
            log.warn("[权限] 读权限拒绝 userId={} deptId={} role={} kbId={}",
                    UserContext.getUserId(), deptId, UserContext.getRole(), kbId);
            throw BizException.forbidden("无权访问该知识库");
        }
    }

    public KbDocument requireDocumentInKnowledgeBase(Long kbId, Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> BizException.notFound("Document not found"));
        if (Boolean.TRUE.equals(doc.getIsDeleted()) || !kbId.equals(doc.getKbId())) {
            throw BizException.notFound("Document not found in this knowledge base");
        }
        return doc;
    }

    public void requireWrite(Long kbId) {
        if (UserContext.isAdmin()) {
            log.debug("[权限] 管理员写权限通过 userId={} kbId={}", UserContext.getUserId(), kbId);
            return;
        }

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();
        boolean hasWritePermission = permissionRepository
                .existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(
                        kbId, "USER", userId, java.util.List.of("WRITE", "ADMIN"))
                || permissionRepository
                .existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(
                        kbId, "DEPARTMENT", deptId, java.util.List.of("WRITE", "ADMIN"));

        if (!hasWritePermission) {
            log.warn("[权限] 写权限拒绝 userId={} deptId={} role={} kbId={}",
                    UserContext.getUserId(), deptId, UserContext.getRole(), kbId);
            throw BizException.forbidden("无文档管理权限");
        }
    }
}
