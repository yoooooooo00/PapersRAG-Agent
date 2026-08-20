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
 * Manages knowledge bases, document records, and indexing tasks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {
    private static final String DEFAULT_KB_NAME = "My Papers";
    private static final String DEFAULT_KB_DESCRIPTION = "Personal paper library";
    private static final String DEFAULT_DEPARTMENT_ID = "PERSONAL";
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

        log.info("[KB] knowledge base created id={} name={} creator={}", saved.getId(), saved.getName(), UserContext.getUserId());
        return saved;
    }

    public List<KnowledgeBaseVO> listAccessible() {
        ensurePersonalKnowledgeBase();
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
    public KnowledgeBase ensurePersonalKnowledgeBase() {
        return kbRepository.findFirstByIsDeletedFalseOrderByIdAsc()
                .orElseGet(this::createPersonalKnowledgeBase);
    }

    private KnowledgeBase createPersonalKnowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(DEFAULT_KB_NAME);
        kb.setDescription(DEFAULT_KB_DESCRIPTION);
        kb.setDepartmentId(resolveDepartmentId());
        kb.setIsPublic(false);
        kb.setCreatedBy(UserContext.getUserId());

        KnowledgeBase saved = kbRepository.save(kb);

        KbPermission perm = new KbPermission();
        perm.setKbId(saved.getId());
        perm.setSubjectType("USER");
        perm.setSubjectId(String.valueOf(UserContext.getUserId()));
        perm.setPermission("ADMIN");
        perm.setGrantedBy(UserContext.getUserId());
        permissionRepository.save(perm);

        log.info("[KB] default personal knowledge base created id={} name={} creator={}",
                saved.getId(), saved.getName(), UserContext.getUserId());
        return saved;
    }

    private String resolveDepartmentId() {
        String departmentId = UserContext.getDepartmentId();
        return departmentId != null && !departmentId.isBlank() ? departmentId : DEFAULT_DEPARTMENT_ID;
    }

    @Transactional
    public KbDocument createDocumentRecord(Long kbId, org.springframework.web.multipart.MultipartFile file) {
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

        log.info("[KB] document record created docId={} fileName={} kbId={}", saved.getId(), fileName, kbId);
        return saved;
    }

    public KbDocument uploadDocument(Long kbId, org.springframework.web.multipart.MultipartFile file) {
        KbDocument saved = createDocumentRecord(kbId, file);
        indexService.submitIndexTask(saved.getId());
        log.info("[KB] document indexing submitted docId={} fileName={} kbId={}", saved.getId(), saved.getFileName(), kbId);
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

        log.info("[KB] document deleted docId={} fileName={}", docId, doc.getFileName());
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
        log.info("[KB] document reindex submitted docId={} newVersion={}", docId, doc.getVersion());
    }

    private void validateFileType(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new RuntimeException("File name must not be empty");
        }
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            throw new RuntimeException("Unsupported file type. Only PDF is supported.");
        }
    }

    private String detectFileType(String fileName) {
        if (fileName == null) return "UNKNOWN";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        return "UNKNOWN";
    }
}