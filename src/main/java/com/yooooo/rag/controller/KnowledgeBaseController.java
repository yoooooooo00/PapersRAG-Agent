package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.dto.DocumentUploadResponse;
import com.yooooo.rag.dto.IndexStatusResponse;
import com.yooooo.rag.dto.KnowledgeBaseCreateRequest;
import com.yooooo.rag.dto.KnowledgeBaseVO;
import com.yooooo.rag.entity.IndexTask;
import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.entity.KnowledgeBase;
import com.yooooo.rag.repository.IndexTaskRepository;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.kb.KnowledgeBaseService;
import com.yooooo.rag.service.permission.PermissionService;
import com.yooooo.rag.service.storage.MinioStorageService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供知识库、文档上传、下载和索引状态查询接口。
 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseController {
    private final PermissionService permissionService;
    private final KnowledgeBaseService kbService;
    private final KbDocumentRepository documentRepository;
    private final IndexTaskRepository taskRepository;
    private final MinioStorageService minioService;

    @GetMapping
    public ApiResponse<List<KnowledgeBaseVO>> list() {
        log.info("[知识库接口] 查询可访问知识库列表 userId={}", UserContext.getUserId());
        return ApiResponse.ok(kbService.listAccessible());
    }

    @PostMapping
    public ApiResponse<KnowledgeBase> create(@RequestBody KnowledgeBaseCreateRequest req) {
        log.info("[知识库接口] 创建知识库 userId={} name={}", UserContext.getUserId(), req.getName());
        KnowledgeBase kb = kbService.create(req);
        log.info("[知识库接口] 知识库创建完成 userId={} kbId={} name={}", UserContext.getUserId(), kb.getId(), kb.getName());
        return ApiResponse.ok(kb);
    }

    @PostMapping("/{kbId}/documents")
    public ApiResponse<DocumentUploadResponse> upload(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file) {
        long start = System.currentTimeMillis();
        log.info("[文档接口] 上传文档开始 userId={} kbId={} fileName={} size={} bytes",
                UserContext.getUserId(), kbId, file.getOriginalFilename(), file.getSize());
        permissionService.requireWrite(kbId);
        KbDocument doc = kbService.uploadDocument(kbId, file);
        log.info("[文档接口] 上传文档已提交索引 userId={} kbId={} docId={} fileName={} elapsed={}ms",
                UserContext.getUserId(), kbId, doc.getId(), doc.getFileName(), System.currentTimeMillis() - start);
        return ApiResponse.ok(DocumentUploadResponse.submitted(doc.getId(), doc.getFileName()));
    }

    @GetMapping("/{kbId}/documents/{docId}/status")
    public ApiResponse<IndexStatusResponse> getStatus(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        log.info("[文档接口] 查询索引状态 userId={} kbId={} docId={}", UserContext.getUserId(), kbId, docId);
        permissionService.requireRead(kbId);

        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在"));
        IndexTask latestTask = taskRepository
                .findTopByDocIdOrderByCreatedAtDesc(docId)
                .orElse(null);

        IndexStatusResponse resp = new IndexStatusResponse();
        resp.setDocId(doc.getId());
        resp.setFileName(doc.getFileName());
        resp.setStatus(doc.getStatus().name());
        resp.setErrorMsg(doc.getErrorMsg());
        resp.setChunkCount(doc.getChunkCount());
        resp.setTokenCount(doc.getTokenCount());
        resp.setIndexedAt(doc.getIndexedAt() != null ? doc.getIndexedAt().toString() : null);
        resp.setRetryCount(latestTask != null ? latestTask.getRetryCount() : 0);
        return ApiResponse.ok(resp);
    }

    @GetMapping("/{kbId}/documents")
    public ApiResponse<List<KbDocument>> listDocuments(@PathVariable Long kbId) {
        log.info("[文档接口] 查询文档列表 userId={} kbId={}", UserContext.getUserId(), kbId);
        permissionService.requireRead(kbId);
        List<KbDocument> docs = documentRepository.findByKbIdAndIsDeletedFalse(kbId);
        log.info("[文档接口] 文档列表查询完成 userId={} kbId={} count={}", UserContext.getUserId(), kbId, docs.size());
        return ApiResponse.ok(docs);
    }

    @DeleteMapping("/{kbId}/documents/{docId}")
    public ApiResponse<Void> deleteDocument(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        log.info("[文档接口] 删除文档 userId={} kbId={} docId={}", UserContext.getUserId(), kbId, docId);
        permissionService.requireWrite(kbId);
        permissionService.requireDocumentInKnowledgeBase(kbId, docId);
        kbService.deleteDocument(docId);
        log.info("[文档接口] 删除文档完成 userId={} kbId={} docId={}", UserContext.getUserId(), kbId, docId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{kbId}/documents/{docId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        long start = System.currentTimeMillis();
        log.info("[文档接口] 下载文档开始 userId={} kbId={} docId={}", UserContext.getUserId(), kbId, docId);
        permissionService.requireRead(kbId);
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在"));
        byte[] content = minioService.download(doc.getMinioPath());
        String encodedName = URLEncoder.encode(doc.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        log.info("[文档接口] 下载文档完成 userId={} kbId={} docId={} fileName={} bytes={} elapsed={}ms",
                UserContext.getUserId(), kbId, docId, doc.getFileName(), content.length, System.currentTimeMillis() - start);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    @PostMapping("/{kbId}/documents/{docId}/reindex")
    public ApiResponse<String> reindex(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        log.info("[文档接口] 重建索引 userId={} kbId={} docId={}", UserContext.getUserId(), kbId, docId);
        permissionService.requireWrite(kbId);
        permissionService.requireDocumentInKnowledgeBase(kbId, docId);
        kbService.reindex(docId);
        log.info("[文档接口] 重建索引任务已提交 userId={} kbId={} docId={}", UserContext.getUserId(), kbId, docId);
        return ApiResponse.ok("重建索引任务已提交，请通过 /status 接口查询进度");
    }
}
