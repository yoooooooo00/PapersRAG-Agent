package com.yooooo.rag.service.document;

import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.service.indexing.IndexService;
import com.yooooo.rag.service.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 处理文档更新、删除和重新索引流程。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentUpdateService {
    private final KbDocumentRepository documentRepository;
    private final MinioStorageService minioService;
    private final IndexService indexService;

    @Transactional
    public String updateDocumentRecord(Long docId, MultipartFile newFile) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        String oldMinioPath = doc.getMinioPath();

        String newMinioPath = minioService.upload(doc.getKbId(), newFile);

        doc.setFileName(newFile.getOriginalFilename());
        doc.setFileSize(newFile.getSize());
        doc.setMinioPath(newMinioPath);
        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PENDING);
        doc.setErrorMsg(null);
        documentRepository.save(doc);

        log.info("[DocumentUpdate] 文档记录更新：docId={}，newVersion={}，newFile={}",
                docId, doc.getVersion(), newFile.getOriginalFilename());
        return oldMinioPath;
    }

    public KbDocument replaceDocument(Long docId, MultipartFile newFile) {
        String oldMinioPath = updateDocumentRecord(docId, newFile);

        indexService.submitIndexTask(docId);

        minioService.delete(oldMinioPath);

        return documentRepository.findById(docId).orElseThrow();
    }

    @Transactional
    public void forceReindex(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PENDING);
        doc.setErrorMsg(null);
        documentRepository.save(doc);
    }

    public void forceReindexAndSubmit(Long docId) {
        forceReindex(docId);
        indexService.submitIndexTask(docId);
        log.info("[DocumentUpdate] 强制重建索引已提交：docId={}", docId);
    }
}
