package com.yooooo.rag.service.kb;

import com.yooooo.rag.dto.KnowledgeBaseCreateRequest;
import com.yooooo.rag.dto.KnowledgeBaseVO;
import com.yooooo.rag.entity.*;
import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.entity.KbPermission;
import com.yooooo.rag.entity.KnowledgeBase;
import com.yooooo.rag.repository.*;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.indexing.IndexService;
import com.yooooo.rag.service.storage.MinioStorageService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理知识库创建、列表、文档上传和文档删除等业务逻辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository kbRepository;
    private final KbPermissionRepository permissionRepository;
    private final KbDocumentRepository documentRepository;
    private final DocChunkRepository chunkRepository;
    private final MinioStorageService minioService;
    private final IndexService indexService;

    @Transactional
    public KnowledgeBase create(KnowledgeBaseCreateRequest req) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName());
        kb.setDescription(req.getDescription());
        kb.setDepartmentId(req.getDepartmentId());
        kb.setIsPublic(req.getIsPublic());
        kb.setCreatedBy(UserContext.getUserId());

        KnowledgeBase saved = kbRepository.save(kb);

        KbPermission perm = new KbPermission();
        perm.setKbId(saved.getId());
        perm.setSubjectType("USER");
        perm.setSubjectId(String.valueOf(UserContext.getUserId()));
        perm.setPermission("ADMIN");
        perm.setGrantedBy(UserContext.getUserId());
        permissionRepository.save(perm);

        log.info("[KB] 知识库创建：id={}，name={}，creator={}", saved.getId(), saved.getName(), UserContext.getUserId());
        return saved;
    }

    public List<KnowledgeBaseVO> listAccessible() {
        String dept = UserContext.getDepartmentId();
        String role = UserContext.getRole();
        String userId = String.valueOf(UserContext.getUserId());

        List<KnowledgeBase> kbList;
        if ("ADMIN".equalsIgnoreCase(role)) {
            kbList = kbRepository.findByIsDeletedFalse();
            return kbList.stream().map(kb -> toVO(kb, "ADMIN")).toList();
        }
        Map<Long, String> permMap = new HashMap<>();

        permissionRepository.findBySubjectTypeAndSubjectId("DEPARTMENT", dept)
                .forEach(p -> permMap.merge(p.getKbId(), p.getPermission(), this::higherPermission));
        permissionRepository.findBySubjectTypeAndSubjectId("USER", userId)
                .forEach(p -> permMap.merge(p.getKbId(), p.getPermission(), this::higherPermission));

        Set<Long> accessibleIds = new HashSet<>(permMap.keySet());
        kbRepository.findByIsPublicTrueAndIsDeletedFalse().forEach(kb -> {
            accessibleIds.add(kb.getId());
            permMap.putIfAbsent(kb.getId(), "READ");
        });

        if (accessibleIds.isEmpty()) return List.of();

        return kbRepository.findAllById(accessibleIds).stream()
                .filter(kb -> !kb.getIsDeleted())
                .map(kb -> toVO(kb, permMap.getOrDefault(kb.getId(), "READ")))
                .toList();
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb, String permission) {
        return KnowledgeBaseVO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .departmentId(kb.getDepartmentId())
                .isPublic(kb.getIsPublic())
                .createdBy(kb.getCreatedBy())
                .createdAt(kb.getCreatedAt())
                .permission(permission)
                .build();
    }

    private static final Map<String, Integer> PERM_LEVEL = Map.of(
            "READ", 1, "WRITE", 2, "ADMIN", 3);

    private String higherPermission(String a, String b) {
        return PERM_LEVEL.getOrDefault(a, 0) >= PERM_LEVEL.getOrDefault(b, 0) ? a : b;
    }

    @Transactional
    public KbDocument uploadDocument(Long kbId, org.springframework.web.multipart.MultipartFile file) {
        String fileName = file.getOriginalFilename();
        validateFileType(fileName);

        String minioPath = minioService.upload(kbId, file);

        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType(detectFileType(fileName));
        doc.setFileSize(file.getSize());
        doc.setMinioPath(minioPath);
        doc.setUploadedBy(UserContext.getUserId());
        KbDocument saved = documentRepository.save(doc);

        indexService.submitIndexTask(saved.getId());

        log.info("[KB] 文档上传：docId={}，fileName={}，kbId={}", saved.getId(), fileName, kbId);
        return saved;
    }

    @Transactional
    public void deleteDocument(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + docId));

        doc.setIsDeleted(true);
        documentRepository.save(doc);
        chunkRepository.deleteByDocId(docId);

        minioService.delete(doc.getMinioPath());

        log.info("[KB] 文档删除：docId={}，fileName={}", docId, doc.getFileName());
    }

    @Transactional
    public void reindex(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + docId));

        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PENDING);
        doc.setErrorMsg(null);
        documentRepository.save(doc);

        indexService.submitIndexTask(docId);
        log.info("[KB] 触发重建索引：docId={}，newVersion={}", docId, doc.getVersion());
    }

    private void validateFileType(String fileName) {
        if (fileName == null) throw new RuntimeException("File name must not be empty");
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".docx") &&
            !lower.endsWith(".md")  && !lower.endsWith(".txt")) {
            throw new RuntimeException("Unsupported file type. Supported: PDF, DOCX, MD, TXT");
        }
    }

    private String detectFileType(String fileName) {
        if (fileName == null) return "UNKNOWN";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".md"))   return "MD";
        if (lower.endsWith(".txt"))  return "TXT";
        return "UNKNOWN";
    }
}
