package com.yooooo.rag.service.indexing;

import com.yooooo.rag.entity.*;
import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.entity.IndexTask;
import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.repository.*;
import com.yooooo.rag.repository.DocChunkRepository;
import com.yooooo.rag.repository.IndexTaskRepository;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.document.DocumentLoaderService;
import com.yooooo.rag.service.embedding.EmbeddingService;
import com.yooooo.rag.service.loader.ParseResult;
import com.yooooo.rag.service.paper.PaperMetadataExtractor;
import com.yooooo.rag.service.splitter.ChunkResult;
import com.yooooo.rag.service.splitter.ChunkService;
import com.yooooo.rag.service.storage.MinioStorageService;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 执行文档索引，把文档分块、生成向量并写入数据库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexService {
    private final KbDocumentRepository documentRepository;
    private final DocChunkRepository chunkRepository;
    private final IndexTaskRepository taskRepository;
    private final DocumentLoaderService loaderService;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final MinioStorageService minioStorageService;
    private final IndexTaskLauncher taskLauncher;
    private final PaperRepository paperRepository;
    private final PaperMetadataExtractor paperMetadataExtractor;
    private final ChunkEmbeddingTextBuilder chunkEmbeddingTextBuilder;

    public void submitIndexTask(Long docId, String textContent) {
        IndexTask task = new IndexTask();
        task.setDocId(docId);
        task.setTaskType("INDEX");
        taskRepository.save(task);
        taskLauncher.launchWithText(
                task.getId(),
                docId,
                textContent,
                UserContext.getUserId(),
                UserContext.getDepartmentId(),
                UserContext.getRole()
        );
    }

    public void submitIndexTask(Long docId) {
        IndexTask task = new IndexTask();
        task.setDocId(docId);
        task.setTaskType("INDEX");
        taskRepository.save(task);

        taskLauncher.launchFromMinio(
                task.getId(),
                docId,
                UserContext.getUserId(),
                UserContext.getDepartmentId(),
                UserContext.getRole()
        );
    }

    public void executeFromMinio(Long taskId, Long docId) {
        KbDocument doc = documentRepository.findById(docId).orElseThrow();
        try {
            byte[] fileBytes = minioStorageService.download(doc.getMinioPath());

            ParseResult parseResult = loaderService.load(
                    new ByteArrayInputStream(fileBytes),
                    doc.getFileName()
            );

            doIndex(taskId, docId, doc, parseResult);
        } catch (Exception e) {
            markFailed(taskId, docId, "从MinIO读取文件失败：" + e.getMessage());
        }
    }

    public void executeWithText(Long taskId, Long docId, String textContent) {
        KbDocument doc = documentRepository.findById(docId).orElseThrow();

        ParseResult parseResult = ParseResult.builder()
                .success(true)
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text(textContent)
                        .build()))
                .totalPages(1)
                .build();

        doIndex(taskId, docId, doc, parseResult);
    }

    private void doIndex(Long taskId, Long docId, KbDocument doc, ParseResult parseResult) {
        updateTaskStatus(taskId, IndexTask.TaskStatus.RUNNING);
        updateDocStatus(docId, KbDocument.DocumentStatus.PROCESSING);

        try {
            if (!parseResult.isSuccess()) {
                throw new RuntimeException("文档解析失败：" + parseResult.getErrorMsg());
            }

            Paper paper = enrichPaperMetadata(docId, doc.getFileName(), parseResult);

            List<ChunkResult> chunks = chunkService.chunk(parseResult);
            List<ChunkEmbeddingTextBuilder.IndexableChunk> indexableChunks = chunkEmbeddingTextBuilder.build(paper, doc.getFileName(), chunks);
            if (indexableChunks.isEmpty()) {
                throw new RuntimeException("分块结果为空，文档可能无有效文本内容");
            }
            log.info("[IndexService] docId={}，分块完成，共{}块", docId, indexableChunks.size());

            List<String> texts = indexableChunks.stream()
                    .map(ChunkEmbeddingTextBuilder.IndexableChunk::embeddingText)
                    .toList();

            List<float[]> embeddings = embeddingService.embedBatch(texts);

            chunkRepository.deleteByDocIdAndDocVersionLessThan(docId, doc.getVersion());

            List<DocChunk> docChunks = new ArrayList<>();
            Long paperId = paper != null ? paper.getId() : resolvePaperId(docId);
            int totalTokens = 0;
            for (int i = 0; i < indexableChunks.size(); i++) {
                ChunkEmbeddingTextBuilder.IndexableChunk indexed = indexableChunks.get(i);
                ChunkResult chunk = indexed.chunk();
                DocChunk docChunk = new DocChunk();
                docChunk.setDocId(docId);
                docChunk.setKbId(doc.getKbId());
                docChunk.setPaperId(paperId);
                docChunk.setChunkIndex(chunk.getChunkIndex());
                docChunk.setContent(chunk.getContent());
                docChunk.setEmbedding(embeddings.get(i));
                docChunk.setPageNum(chunk.getPageNum());
                docChunk.setSectionTitle(chunk.getSectionTitle());
                docChunk.setSectionType(chunk.getSectionType());
                docChunk.setContentType(chunk.getContentType());
                docChunk.setTokenCount(chunk.getEstimatedTokens());
                docChunk.setDocVersion(doc.getVersion());
                docChunks.add(docChunk);
                totalTokens += chunk.getEstimatedTokens();
            }

            batchInsertChunks(docChunks);

            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc.setChunkCount(indexableChunks.size());
            doc.setTokenCount(totalTokens);
            doc.setIndexedAt(LocalDateTime.now());
            documentRepository.save(doc);

            updateTaskStatus(taskId, IndexTask.TaskStatus.DONE);

            log.info("[IndexService] 索引完成：docId={}，chunks={}，tokens={}",
                    docId, indexableChunks.size(), totalTokens);

        } catch (Exception e) {
            log.error("[IndexService] 索引失败：docId={}，error={}", docId, e.getMessage(), e);
            markFailed(taskId, docId, e.getMessage());
            retryIfPossible(taskId, docId);
        }
    }

    private String resolveSectionType(ChunkResult chunk) {
        if (chunk.getSectionType() != null && !chunk.getSectionType().isBlank()) {
            return chunk.getSectionType();
        }
        return inferSectionType(chunk.getSectionTitle(), chunk.getContent());
    }
    private Long resolvePaperId(Long docId) {
        return paperRepository.findFirstByDocIdAndIsDeletedFalse(docId)
                .map(Paper::getId)
                .orElse(null);
    }

    private Paper enrichPaperMetadata(Long docId, String fileName, ParseResult parseResult) {
        Paper paper = paperRepository.findFirstByDocIdAndIsDeletedFalse(docId).orElse(null);
        if (paper != null && paperMetadataExtractor.fillMissingMetadata(paper, parseResult, fileName)) {
            paperRepository.save(paper);
            log.info("[IndexService] enriched paper metadata: paperId={} docId={}", paper.getId(), docId);
        }
        return paper;
    }

    private String inferSectionType(String sectionTitle, String content) {
        String value = firstNonBlank(sectionTitle, firstLine(content));
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase();
        if (normalized.contains("abstract")) return "ABSTRACT";
        if (normalized.contains("introduction")) return "INTRODUCTION";
        if (normalized.contains("related work") || normalized.contains("prior work")) return "RELATED_WORK";
        if (normalized.contains("background")) return "BACKGROUND";
        if (normalized.contains("method") || normalized.contains("approach") || normalized.contains("model")) return "METHOD";
        if (normalized.contains("experiment") || normalized.contains("evaluation") || normalized.contains("setup")) return "EXPERIMENTS";
        if (normalized.contains("result") || normalized.contains("analysis")) return "RESULTS";
        if (normalized.contains("discussion")) return "DISCUSSION";
        if (normalized.contains("limitation")) return "LIMITATIONS";
        if (normalized.contains("conclusion") || normalized.contains("future work")) return "CONCLUSION";
        if (normalized.contains("reference") || normalized.equals("bibliography")) return "REFERENCES";
        if (normalized.contains("appendix") || normalized.contains("supplement")) return "APPENDIX";
        return null;
    }

    private String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] lines = content.split("\\R");
        for (String line : lines) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        if (second != null && !second.isBlank()) {
            return second.strip();
        }
        return null;
    }
    private void batchInsertChunks(List<DocChunk> chunks) {
        int batchSize = 50;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<DocChunk> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            chunkRepository.saveAll(batch);
            log.debug("[IndexService] 写入批次 {}/{}",
                    i / batchSize + 1,
                    (chunks.size() + batchSize - 1) / batchSize);
        }
    }

    private void markFailed(Long taskId, Long docId, String errorMsg) {
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(IndexTask.TaskStatus.FAILED);
        task.setErrorMsg(errorMsg);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);

        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus(KbDocument.DocumentStatus.FAILED);
            doc.setErrorMsg(errorMsg);
            documentRepository.save(doc);
        });
    }

    private void retryIfPossible(Long taskId, Long docId) {
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        if (task.canRetry()) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(IndexTask.TaskStatus.PENDING);
            taskRepository.save(task);
            log.info("[IndexService] 任务将重试：taskId={}，retryCount={}",
                    taskId, task.getRetryCount());

            scheduleRetry(taskId, docId, task.getRetryCount());
        }
    }

    protected void scheduleRetry(Long taskId, Long docId, int retryCount) {
        try {
            long delay = (long) Math.pow(2, retryCount - 1) * 1000;
            Thread.sleep(delay);
            executeFromMinio(taskId, docId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateTaskStatus(Long taskId, IndexTask.TaskStatus status) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(status);

            if (status == IndexTask.TaskStatus.RUNNING) t.setStartedAt(LocalDateTime.now());

            if (status == IndexTask.TaskStatus.DONE)    t.setFinishedAt(LocalDateTime.now());
            taskRepository.save(t);
        });
    }

    private void updateDocStatus(Long docId, KbDocument.DocumentStatus status) {
        documentRepository.findById(docId).ifPresent(d -> {
            d.setStatus(status);
            documentRepository.save(d);
        });
    }
}
