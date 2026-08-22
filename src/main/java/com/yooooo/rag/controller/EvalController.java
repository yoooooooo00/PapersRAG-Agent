package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.dto.EvalReport;
import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.entity.EvalDataset;
import com.yooooo.rag.entity.EvalResult;
import com.yooooo.rag.entity.EvalTask;
import com.yooooo.rag.exception.BizException;
import com.yooooo.rag.repository.DocChunkRepository;
import com.yooooo.rag.repository.EvalDatasetRepository;
import com.yooooo.rag.repository.EvalResultRepository;
import com.yooooo.rag.repository.EvalTaskRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.eval.EvalService;
import com.yooooo.rag.service.permission.PermissionService;
import com.yooooo.rag.service.retrieval.QueryRoutingService;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Evaluation and chunk inspection APIs.
 */
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {
    private final EvalService evalService;
    private final EvalDatasetRepository datasetRepository;
    private final EvalResultRepository resultRepository;
    private final EvalTaskRepository taskRepository;
    private final DocChunkRepository chunkRepository;
    private final PermissionService permissionService;
    private final com.yooooo.rag.service.eval.EvalTaskLauncher evalTaskLauncher;

    @PostMapping("/{kbId}/run")
    public ApiResponse<EvalTaskView> runEval(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "latest") String version) {
        permissionService.requireRead(kbId);
        EvalTask task = evalService.submitEvaluation(kbId, version);
        evalTaskLauncher.launch(task.getId(), kbId, version);
        return ApiResponse.ok(toTaskView(task));
    }

    @GetMapping("/{kbId}/history")
    public ApiResponse<List<EvalReport>> getHistory(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        return ApiResponse.ok(evalService.compareVersions(kbId));
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<EvalTaskView> getTask(@PathVariable Long taskId) {
        EvalTask task = evalService.getTask(taskId);
        permissionService.requireRead(task.getKbId());
        return ApiResponse.ok(toTaskView(task));
    }

    @GetMapping("/task/{taskId}/results")
    public ApiResponse<List<EvalResultView>> listResultsByTask(@PathVariable Long taskId) {
        EvalTask task = evalService.getTask(taskId);
        permissionService.requireRead(task.getKbId());
        return ApiResponse.ok(buildResultViews(task.getKbId(), resultRepository.findByTaskIdOrderByEvalAtDesc(taskId)));
    }

    @GetMapping("/{kbId}/results")
    public ApiResponse<List<EvalResultView>> listResults(
            @PathVariable Long kbId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(defaultValue = "latest") String version) {
        permissionService.requireRead(kbId);

        if (taskId != null) {
            EvalTask task = evalService.getTask(taskId);
            if (!kbId.equals(task.getKbId())) {
                throw BizException.badRequest("Evaluation task does not belong to this knowledge base");
            }
            return ApiResponse.ok(buildResultViews(kbId, resultRepository.findByTaskIdOrderByEvalAtDesc(taskId)));
        }

        if ("latest".equalsIgnoreCase(version)) {
            List<EvalTask> tasks = taskRepository.findByKbIdOrderByCreatedAtDesc(kbId);
            if (!tasks.isEmpty()) {
                Long latestTaskId = tasks.get(0).getId();
                return ApiResponse.ok(buildResultViews(kbId, resultRepository.findByTaskIdOrderByEvalAtDesc(latestTaskId)));
            }
            return ApiResponse.ok(List.of());
        }

        Map<Long, EvalDataset> datasetMap = datasetRepository.findByKbId(kbId).stream()
                .collect(Collectors.toMap(EvalDataset::getId, Function.identity()));
        List<Long> datasetIds = datasetMap.keySet().stream().toList();
        if (datasetIds.isEmpty()) {
            return ApiResponse.ok(List.of());
        }

        List<EvalResultView> views = resultRepository
                .findByDatasetIdInAndEvalVersionOrderByEvalAtDesc(datasetIds, version)
                .stream()
                .map(result -> toView(datasetMap.get(result.getDatasetId()), result))
                .toList();
        return ApiResponse.ok(views);
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
        item.setExpectedRoute(req.getExpectedRoute());
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
        if (req.getExpectedRoute() != null) item.setExpectedRoute(req.getExpectedRoute());
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
        List<ChunkSummary> summaries = chunks.stream()
                .sorted(Comparator.comparing(DocChunk::getDocId)
                        .thenComparing(c -> c.getPageNum() == null ? Integer.MAX_VALUE : c.getPageNum())
                        .thenComparing(c -> c.getChunkIndex() == null ? Integer.MAX_VALUE : c.getChunkIndex()))
                .map(c -> {
                    ChunkSummary s = new ChunkSummary();
                    s.setId(c.getId());
                    s.setDocId(c.getDocId());
                    s.setPaperId(c.getPaperId());
                    s.setChunkIndex(c.getChunkIndex());
                    s.setPageNum(c.getPageNum());
                    s.setSectionTitle(c.getSectionTitle());
                    s.setSectionType(c.getSectionType());
                    s.setContentType(c.getContentType());
                    s.setTableCaption(c.getTableCaption());
                    s.setContent(c.getContent().length() > 200
                            ? c.getContent().substring(0, 200) + "..."
                            : c.getContent());
                    s.setTokenCount(c.getTokenCount());
                    return s;
                }).toList();
        return ApiResponse.ok(summaries);
    }

    private List<EvalResultView> buildResultViews(Long kbId, List<EvalResult> results) {
        Map<Long, EvalDataset> datasetMap = datasetRepository.findByKbId(kbId).stream()
                .collect(Collectors.toMap(EvalDataset::getId, Function.identity()));
        return results.stream()
                .map(result -> toView(datasetMap.get(result.getDatasetId()), result))
                .toList();
    }

    private EvalTaskView toTaskView(EvalTask task) {
        EvalTaskView view = new EvalTaskView();
        view.setId(task.getId());
        view.setKbId(task.getKbId());
        view.setEvalVersion(task.getEvalVersion());
        view.setStatus(task.getStatus());
        view.setTotalQuestions(task.getTotalQuestions());
        view.setProcessedQuestions(task.getProcessedQuestions());
        view.setHitCount(task.getHitCount());
        view.setHitRate(task.getHitRate());
        view.setMrr(task.getMrr());
        view.setAvgFaithfulness(task.getAvgFaithfulness());
        view.setErrorMsg(task.getErrorMsg());
        view.setCreatedBy(task.getCreatedBy());
        view.setCreatedAt(task.getCreatedAt());
        view.setStartedAt(task.getStartedAt());
        view.setFinishedAt(task.getFinishedAt());
        return view;
    }

    private EvalResultView toView(EvalDataset dataset, EvalResult result) {
        EvalResultView view = new EvalResultView();
        view.setTaskId(result.getTaskId());
        view.setDatasetId(result.getDatasetId());
        view.setQuestion(dataset == null ? null : dataset.getQuestion());
        view.setExpectedAnswer(dataset == null ? null : dataset.getExpectedAnswer());
        view.setExpectedChunkIds(dataset == null ? null : dataset.getExpectedChunkIds());
        view.setExpectedRoute(dataset == null ? null : dataset.getExpectedRoute());
        view.setRetrievedChunkIds(result.getRetrievedChunkIds());
        view.setUsedChunkIds(result.getUsedChunkIds());
        view.setQueryRoute(result.getQueryRoute());
        view.setRouteMatch(dataset != null
                && dataset.getExpectedRoute() != null
                && result.getQueryRoute() != null
                && dataset.getExpectedRoute() == result.getQueryRoute());
        view.setHit(result.getHit());
        view.setRank(result.getRank());
        view.setActualAnswer(result.getActualAnswer());
        view.setFaithfulness(result.getFaithfulness());
        view.setEvalVersion(result.getEvalVersion());
        view.setEvalAt(result.getEvalAt());
        return view;
    }

    /**
     * Request payload for adding or updating an evaluation question.
     */
    @Data
    public static class EvalDatasetRequest {
        private String question;
        private String expectedAnswer;
        private Long[] expectedChunkIds;
        private QueryRoutingService.QueryRoute expectedRoute;
    }

    /**
     * Chunk summary for inspection.
     */
    @Data
    public static class ChunkSummary {
        private Long id;
        private Long docId;
        private Long paperId;
        private Integer chunkIndex;
        private Integer pageNum;
        private String sectionTitle;
        private String sectionType;
        private String contentType;
        private String tableCaption;
        private String content;
        private Integer tokenCount;
    }

    /**
     * Async task summary returned by the submit endpoint.
     */
    @Data
    public static class EvalTaskView {
        private Long id;
        private Long kbId;
        private String evalVersion;
        private EvalTask.TaskStatus status;
        private Integer totalQuestions;
        private Integer processedQuestions;
        private Integer hitCount;
        private Double hitRate;
        private Double mrr;
        private Double avgFaithfulness;
        private String errorMsg;
        private Long createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
    }

    /**
     * Evaluation result view with the recorded routing decision.
     */
    @Data
    public static class EvalResultView {
        private Long taskId;
        private Long datasetId;
        private String question;
        private String expectedAnswer;
        private Long[] expectedChunkIds;
        private QueryRoutingService.QueryRoute expectedRoute;
        private Long[] retrievedChunkIds;
        private Long[] usedChunkIds;
        private QueryRoutingService.QueryRoute queryRoute;
        private Boolean routeMatch;
        private Boolean hit;
        private Integer rank;
        private String actualAnswer;
        private Double faithfulness;
        private String evalVersion;
        private LocalDateTime evalAt;
    }
}
