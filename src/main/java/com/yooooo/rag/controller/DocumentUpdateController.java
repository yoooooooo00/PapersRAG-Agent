package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.service.document.DocumentUpdateService;
import com.yooooo.rag.service.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供文档更新、删除和重新索引相关接口。
 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class DocumentUpdateController {
    private final PermissionService permissionService;
    private final DocumentUpdateService documentUpdateService;

    @PutMapping("/{kbId}/documents/{docId}/content")
    public ApiResponse<KbDocument> replaceContent(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @RequestParam("file") MultipartFile file) {
        permissionService.requireWrite(kbId);
        permissionService.requireDocumentInKnowledgeBase(kbId, docId);
        return ApiResponse.ok(documentUpdateService.replaceDocument(docId, file));
    }
    @PostMapping("/{kbId}/documents/{docId}/reindex-force")
    public ApiResponse<Void> forceReindex(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        permissionService.requireWrite(kbId);
        permissionService.requireDocumentInKnowledgeBase(kbId, docId);
        documentUpdateService.forceReindexAndSubmit(docId);
        return ApiResponse.ok(null);
    }
}
