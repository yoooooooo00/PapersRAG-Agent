package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.dto.EvalReport;
import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.entity.EvalDataset;
import com.yooooo.rag.exception.BizException;
import com.yooooo.rag.repository.DocChunkRepository;
import com.yooooo.rag.repository.EvalDatasetRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.eval.EvalService;
import com.yooooo.rag.service.permission.PermissionService;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 提供检索和回答效果评估接口。
 */
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {
    private final EvalService evalService;
    private final EvalDatasetRepository datasetRepository;
    private final DocChunkRepository chunkRepository;
    private final PermissionService permissionService;

    @PostMapping("/{kbId}/run")
    public ApiResponse<EvalReport> runEval(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "latest") String version) {
        permissionService.requireRead(kbId);
        EvalReport report = evalService.runEvaluation(kbId, version);
        return ApiResponse.ok(report);
    }

    @GetMapping("/{kbId}/history")
    public ApiResponse<List<EvalReport>> getHistory(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        return ApiResponse.ok(evalService.compareVersions(kbId));
    }

    @GetMapping("/{kbId}/dataset")
    public ApiResponse<List<EvalDataset>> listDataset(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        return ApiResponse.ok(datasetRepository.findByKbId(kbId));
    }

    @PostMapping("/{kbId}/dataset")
    public ApiResponse<EvalDataset> addQuestion(
            @PathVariable Long kbId,
            @RequestBody EvalDatasetRequest req) {
        permissionService.requireWrite(kbId);
        EvalDataset item = new EvalDataset();
        item.setKbId(kbId);
        item.setQuestion(req.getQuestion());
        item.setExpectedAnswer(req.getExpectedAnswer());
        item.setExpectedChunkIds(req.getExpectedChunkIds());
        item.setCreatedBy(UserContext.getUserId());
        return ApiResponse.ok(datasetRepository.save(item));
    }

    @PutMapping("/{kbId}/dataset/{id}")
    public ApiResponse<EvalDataset> updateQuestion(
            @PathVariable Long kbId,
            @PathVariable Long id,
            @RequestBody EvalDatasetRequest req) {
        permissionService.requireWrite(kbId);
        EvalDataset item = datasetRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("Evaluation dataset not found"));
        if (!kbId.equals(item.getKbId())) {
            throw BizException.badRequest("Evaluation dataset does not belong to this knowledge base");
        }
        if (req.getQuestion() != null) item.setQuestion(req.getQuestion());
        if (req.getExpectedAnswer() != null) item.setExpectedAnswer(req.getExpectedAnswer());
        if (req.getExpectedChunkIds() != null) item.setExpectedChunkIds(req.getExpectedChunkIds());
        return ApiResponse.ok(datasetRepository.save(item));
    }

    @DeleteMapping("/{kbId}/dataset/{id}")
    public ApiResponse<Void> deleteQuestion(
            @PathVariable Long kbId,
            @PathVariable Long id) {
        permissionService.requireWrite(kbId);
        EvalDataset item = datasetRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("Evaluation dataset not found"));
        if (!kbId.equals(item.getKbId())) {
            throw BizException.badRequest("Evaluation dataset does not belong to this knowledge base");
        }
        datasetRepository.delete(item);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{kbId}/chunks")
    public ApiResponse<List<ChunkSummary>> listChunks(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        List<DocChunk> chunks = chunkRepository.findByKbId(kbId);
        List<ChunkSummary> summaries = chunks.stream().map(c -> {
            ChunkSummary s = new ChunkSummary();
            s.setId(c.getId());
            s.setDocId(c.getDocId());
            s.setChunkIndex(c.getChunkIndex());
            s.setContent(c.getContent().length() > 200
                    ? c.getContent().substring(0, 200) + "..."
                    : c.getContent());
            s.setTokenCount(c.getTokenCount());
            return s;
        }).toList();
        return ApiResponse.ok(summaries);
    }
/**
 * 新增评估问题时使用的请求参数。
 */

    @Data
    public static class EvalDatasetRequest {
        private String question;
        private String expectedAnswer;
        private Long[] expectedChunkIds;
    }
/**
 * 评估接口返回的候选分块摘要。
 */

    @Data
    public static class ChunkSummary {
        private Long id;
        private Long docId;
        private Integer chunkIndex;
        private String content;
        private Integer tokenCount;
    }
}
