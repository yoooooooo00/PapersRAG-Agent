package com.yooooo.rag.service.eval;

import com.yooooo.rag.dto.EvalReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.entity.EvalDataset;
import com.yooooo.rag.entity.EvalResult;
import com.yooooo.rag.entity.EvalTask;
import com.yooooo.rag.repository.EvalDatasetRepository;
import com.yooooo.rag.repository.EvalResultRepository;
import com.yooooo.rag.repository.EvalTaskRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.rag.HallucinationChecker;
import com.yooooo.rag.service.rag.RagContextBuilder;
import com.yooooo.rag.service.rag.StreamingRagService;
import com.yooooo.rag.service.retrieval.ConfidenceFilter;
import com.yooooo.rag.service.retrieval.ContextTrimmerService;
import com.yooooo.rag.service.retrieval.EnhancedRetrieverService;
import com.yooooo.rag.service.retrieval.HybridRetrieverService;
import com.yooooo.rag.service.retrieval.QueryRoutingService;
import com.yooooo.rag.service.retrieval.RerankerService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Run retrieval and QA evaluation and generate hit-rate / answer-quality reports.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalService {
    private final EvalDatasetRepository datasetRepository;
    private final EvalResultRepository resultRepository;
    private final EvalTaskRepository taskRepository;
    private final HybridRetrieverService hybridRetriever;
    private final EnhancedRetrieverService enhancedRetriever;
    private final ConfidenceFilter confidenceFilter;
    private final ContextTrimmerService contextTrimmer;
    private final RagContextBuilder contextBuilder;
    private final RerankerService rerankerService;
    private final HallucinationChecker hallucinationChecker;
    private final StreamingRagService ragService;
    private final QueryRoutingService queryRoutingService;
    private final ObjectMapper objectMapper;

    @Value("${reranker.top-n:5}")
    private int rerankerTopN;

    @Value("${rag.routing.simple-top-n:5}")
    private int simpleTopN;

    @Value("${rag.routing.standard-top-n:10}")
    private int standardTopN;

    @Value("${rag.routing.complex-candidate-top-n:20}")
    private int complexCandidateTopN;

    @Value("${rag.routing.simple-as-standard-experiment:false}")
    private boolean simpleAsStandardExperiment;

    public EvalTask submitEvaluation(Long kbId, String evalVersion) {
        List<EvalDataset> questions = datasetRepository.findByKbId(kbId);
        if (questions.isEmpty()) {
            throw new RuntimeException("Knowledge base " + kbId + " has no evaluation dataset yet.");
        }

        EvalTask task = new EvalTask();
        task.setKbId(kbId);
        task.setEvalVersion(evalVersion);
        task.setTotalQuestions(questions.size());
        task.setProcessedQuestions(0);
        task.setHitCount(0);
        task.setCreatedBy(UserContext.getUserId());
        task.setStatus(EvalTask.TaskStatus.PENDING);
        return taskRepository.save(task);
    }

    public void executeAsync(Long taskId, Long kbId, String evalVersion) {
        EvalTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Evaluation task not found: " + taskId));
        task.setStatus(EvalTask.TaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskRepository.save(task);

        try {
            EvalReport report = runEvaluation(taskId, kbId, evalVersion);
            task.setStatus(EvalTask.TaskStatus.DONE);
            task.setProcessedQuestions(task.getTotalQuestions());
            task.setHitCount((int) report.getHitCount());
            task.setHitRate(report.getHitRate());
            task.setMrr(report.getMrr());
            task.setAvgFaithfulness(report.getAvgFaithfulness());
            task.setFinishedAt(report.getEvalAt());
            taskRepository.save(task);
            log.info("[EvalTask] done taskId={} kbId={} version={}", taskId, kbId, evalVersion);
        } catch (Exception e) {
            task.setStatus(EvalTask.TaskStatus.FAILED);
            task.setErrorMsg(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
            log.error("[EvalTask] failed taskId={} kbId={} version={} reason={}", taskId, kbId, evalVersion, e.getMessage(), e);
        }
    }

    public EvalReport runEvaluation(Long kbId, String evalVersion) {
        return runEvaluation(null, kbId, evalVersion);
    }

    public EvalReport runEvaluation(Long taskId, Long kbId, String evalVersion) {
        List<EvalDataset> questions = datasetRepository.findByKbId(kbId);
        if (questions.isEmpty()) {
            throw new RuntimeException("Knowledge base " + kbId + " has no evaluation dataset yet.");
        }

        log.info("[Eval] start kbId={} version={} questionCount={}", kbId, evalVersion, questions.size());

        List<EvalResult> results = new ArrayList<>();
        int hits = 0;
        int annotatedQuestions = 0;
        double mrr = 0.0;
        double totalFaithfulness = 0.0;
        int evalCount = 0;
        int processed = 0;
        Map<QueryRoutingService.QueryRoute, long[]> routeStats = new EnumMap<>(QueryRoutingService.QueryRoute.class);

        for (EvalDataset question : questions) {
            try {
                EvalResult result = evaluateOne(question, taskId, kbId, evalVersion);
                results.add(result);

                if (hasExpectedChunks(question)) {
                    annotatedQuestions++;
                    if (Boolean.TRUE.equals(result.getHit())) {
                        hits++;
                    }
                    if (result.getRank() != null && result.getRank() > 0) {
                        mrr += 1.0 / result.getRank();
                    }
                    if (result.getQueryRoute() != null) {
                        long[] stats = routeStats.computeIfAbsent(result.getQueryRoute(), r -> new long[2]);
                        stats[0]++;
                        if (Boolean.TRUE.equals(result.getHit())) {
                            stats[1]++;
                        }
                    }
                }

                if (result.getFaithfulness() != null) {
                    totalFaithfulness += result.getFaithfulness();
                    evalCount++;
                }
            } catch (Exception e) {
                log.error("[Eval] question failed questionId={} error={}", question.getId(), e.getMessage(), e);
            } finally {
                processed++;
                if (taskId != null) {
                    updateTaskProgress(taskId, processed);
                }
            }
        }

        resultRepository.saveAll(results);

        double hitRate = annotatedQuestions == 0 ? 0 : (double) hits / annotatedQuestions;
        double mrrScore = annotatedQuestions == 0 ? 0 : mrr / annotatedQuestions;
        double avgFaithfulness = evalCount == 0 ? 0 : totalFaithfulness / evalCount;

        EvalReport report = EvalReport.builder()
                .kbId(kbId)
                .evalVersion(evalVersion)
                .totalQuestions(questions.size())
                .hitCount(hits)
                .hitRate(hitRate)
                .mrr(mrrScore)
                .avgFaithfulness(avgFaithfulness)
                .evalAt(LocalDateTime.now())
                .build();

        log.info("[Eval] done hitRate={} MRR={} faithfulness={} annotationCoverage={}/{}",
                String.format("%.2f", hitRate * 100),
                String.format("%.4f", mrrScore),
                String.format("%.4f", avgFaithfulness),
                annotatedQuestions,
                questions.size());
        logRouteHitRates(routeStats);

        return report;
    }

    private EvalResult evaluateOne(EvalDataset question, Long taskId, Long kbId, String evalVersion) {
        QueryRoutingService.QueryRoute route = queryRoutingService.classify(question.getQuestion());
        HybridRetrieverService.RetrievalOutcome retrieval = retrieveByRouteWithTrace(route, question.getQuestion(), kbId);
        List<HybridRetrieverService.ScoredChunk> candidates = retrieval.getChunks();
        Long[] retrievedChunkIds = candidates.stream()
                .map(HybridRetrieverService.ScoredChunk::id)
                .toArray(Long[]::new);
        List<HybridRetrieverService.ScoredChunk> filtered = candidates;
        List<HybridRetrieverService.ScoredChunk> trimmed = contextTrimmer.trim(filtered);

        Long[] expectedChunkIds = question.getExpectedChunkIds();
        boolean hit = false;
        Integer rank = null;

        if (expectedChunkIds != null && expectedChunkIds.length > 0) {
            Set<Long> expected = Arrays.stream(expectedChunkIds)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            for (int i = 0; i < filtered.size(); i++) {
                if (expected.contains(filtered.get(i).id())) {
                    hit = true;
                    rank = i + 1;
                    break;
                }
            }
        }

        String actualAnswer = null;
        Double faithfulness = null;
        Long[] usedChunkIds = new Long[0];
        QueryRoutingService.QueryRoute answerRoute = null;
        Long[] answerRetrievedChunkIds = new Long[0];
        Long[] answerTrimmedChunkIds = new Long[0];

        if (question.getExpectedAnswer() != null) {
            RagResponse response = ragService.syncQuery(question.getQuestion(), List.of(kbId), "eval-session");
            actualAnswer = response.getAnswer();
            answerRoute = response.getQueryRoute();
            answerRetrievedChunkIds = response.getRetrievedChunkIds() == null ? new Long[0] : response.getRetrievedChunkIds();
            answerTrimmedChunkIds = response.getTrimmedChunkIds() == null ? new Long[0] : response.getTrimmedChunkIds();
            usedChunkIds = response.getSources() == null ? new Long[0] : response.getSources().stream()
                    .map(RagResponse.Source::getChunkId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toArray(Long[]::new);

            String context = contextBuilder.buildContext(trimmed);
            HallucinationChecker.FaithfulnessResult faithResult =
                    hallucinationChecker.check(question.getQuestion(), actualAnswer, context);
            faithfulness = faithResult.score();
        }

        EvalResult result = new EvalResult();
        result.setTaskId(taskId);
        result.setDatasetId(question.getId());
        result.setEvalVersion(evalVersion);
        result.setExpectedRoute(question.getExpectedRoute());
        result.setQueryRoute(route);
        result.setHit(hit);
        result.setRank(rank);
        result.setRetrievedChunkIds(retrievedChunkIds);
        result.setUsedChunkIds(usedChunkIds);
        result.setActualAnswer(actualAnswer);
        result.setRouteTrace(toJson(buildRouteTrace(question, route, answerRoute, hit, rank, retrievedChunkIds, usedChunkIds)));
        result.setRetrievalTrace(toJson(retrieval.getTrace()));
        result.setFinalTrace(toJson(buildFinalTrace(question, route, answerRoute, retrievedChunkIds, answerRetrievedChunkIds, answerTrimmedChunkIds, trimmed, usedChunkIds, hit, rank)));
        result.setFaithfulness(faithfulness);
        result.setEvalAt(LocalDateTime.now());

        log.info("[Eval] questionId={} route={} hit={} rank={}", question.getId(), route, hit, rank);
        return result;
    }

    private HybridRetrieverService.RetrievalOutcome retrieveByRouteWithTrace(
            QueryRoutingService.QueryRoute route,
            String question,
            Long kbId) {
        List<Long> kbIds = List.of(kbId);
        return switch (route) {
            case SIMPLE -> simpleAsStandardExperiment
                    ? hybridRetriever.retrieveWithTrace(question, kbIds, standardTopN)
                    : hybridRetriever.retrieveVectorOnlyWithTrace(question, kbIds, simpleTopN);
            case STANDARD -> hybridRetriever.retrieveWithTrace(question, kbIds, standardTopN);
            case COMPLEX -> enhancedRetriever.retrieveWithTrace(question, kbIds, complexCandidateTopN);
        };
    }

    private Map<String, Object> buildRouteTrace(
            EvalDataset question,
            QueryRoutingService.QueryRoute evalRoute,
            QueryRoutingService.QueryRoute answerRoute,
            boolean hit,
            Integer rank,
            Long[] retrievedChunkIds,
            Long[] usedChunkIds) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("datasetId", question.getId());
        trace.put("expectedRoute", question.getExpectedRoute());
        trace.put("evalRoute", evalRoute);
        trace.put("answerRoute", answerRoute);
        trace.put("routeMatch", question.getExpectedRoute() != null && question.getExpectedRoute() == evalRoute);
        trace.put("hit", hit);
        trace.put("rank", rank);
        trace.put("retrievedChunkIds", retrievedChunkIds);
        trace.put("usedChunkIds", usedChunkIds);
        return trace;
    }

    private Map<String, Object> buildFinalTrace(
            EvalDataset question,
            QueryRoutingService.QueryRoute evalRoute,
            QueryRoutingService.QueryRoute answerRoute,
            Long[] evalRetrievedChunkIds,
            Long[] answerRetrievedChunkIds,
            Long[] answerTrimmedChunkIds,
            List<HybridRetrieverService.ScoredChunk> evalTrimmedChunks,
            Long[] usedChunkIds,
            boolean hit,
            Integer rank) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("datasetId", question.getId());
        trace.put("expectedChunkIds", question.getExpectedChunkIds());
        trace.put("evalRoute", evalRoute);
        trace.put("answerRoute", answerRoute);
        trace.put("evalRetrievedChunkIds", evalRetrievedChunkIds);
        trace.put("evalTrimmedChunkIds", evalTrimmedChunks.stream().map(HybridRetrieverService.ScoredChunk::id).toArray(Long[]::new));
        trace.put("answerRetrievedChunkIds", answerRetrievedChunkIds);
        trace.put("answerTrimmedChunkIds", answerTrimmedChunkIds);
        trace.put("usedChunkIds", usedChunkIds);
        trace.put("hit", hit);
        trace.put("rank", rank);
        trace.put("retrievalTruncated", evalTrimmedChunks.size() < evalRetrievedChunkIds.length);
        trace.put("answerTruncated", answerTrimmedChunkIds.length < answerRetrievedChunkIds.length);
        return trace;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[Eval] failed to serialize trace: {}", e.getMessage());
            return String.valueOf(value);
        }
    }

    private void logRouteHitRates(Map<QueryRoutingService.QueryRoute, long[]> routeStats) {
        String summary = Arrays.stream(QueryRoutingService.QueryRoute.values())
                .map(route -> {
                    long[] stats = routeStats.get(route);
                    long total = stats == null ? 0 : stats[0];
                    long hits = stats == null ? 0 : stats[1];
                    double rate = total == 0 ? 0.0 : (double) hits / total;
                    return route + "=" + String.format("%.2f%%(%d/%d)", rate * 100, hits, total);
                })
                .collect(Collectors.joining(", "));
        log.info("[Eval] route hit rates {}", summary);
    }

    private List<HybridRetrieverService.ScoredChunk> retrieveByRoute(
            QueryRoutingService.QueryRoute route,
            String question,
            Long kbId) {
        List<Long> kbIds = List.of(kbId);
        return switch (route) {
            case SIMPLE -> simpleAsStandardExperiment
                    ? hybridRetriever.retrieve(question, kbIds, standardTopN)
                    : hybridRetriever.retrieveVectorOnly(question, kbIds, simpleTopN);
            case STANDARD -> hybridRetriever.retrieve(question, kbIds, standardTopN);
            case COMPLEX -> {
                var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, complexCandidateTopN);
                yield rerankerService.rerank(question, candidates, rerankerTopN);
            }
        };
    }

    private void updateTaskProgress(Long taskId, int processedQuestions) {
        EvalTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Evaluation task not found: " + taskId));
        task.setProcessedQuestions(processedQuestions);
        taskRepository.save(task);
    }

    private boolean hasExpectedChunks(EvalDataset question) {
        return question.getExpectedChunkIds() != null && question.getExpectedChunkIds().length > 0;
    }

    public List<EvalReport> compareVersions(Long kbId) {
        return resultRepository.aggregateByVersion(kbId);
    }

    public EvalTask getTask(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("Evaluation task not found: " + taskId));
    }
}

