package com.yooooo.rag.service.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yooooo.rag.service.metrics.TokenMetrics;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 调用重排模型对候选文本块重新排序。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RerankerService {
    private final WebClient.Builder webClientBuilder;
    private final TokenMetrics tokenMetrics;
    @Value("${reranker.endpoint}")
    private String endpoint;
    @Value("${reranker.api-key}")
    private String apiKey;
    @Value("${reranker.model:gte-rerank}")
    private String model;
    @Value("${reranker.timeout-ms:800}")
    private long timeoutMs;
    @Value("${reranker.top-n:5}")
    private int defaultTopN;
    @Value("${reranker.enabled:false}")
    private boolean enabled;

    public List<HybridRetrieverService.ScoredChunk> rerank(
            String question,
            List<HybridRetrieverService.ScoredChunk> candidates,
            int topN) {
        if (candidates.isEmpty()) return candidates;
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return candidates.stream().limit(topN).collect(Collectors.toList());
        }

        if (candidates.size() <= topN) {
            return candidates;
        }
        try {
            List<HybridRetrieverService.ScoredChunk> reranked =
                    callRerankApi(question, candidates, topN);
            log.info("[Reranker] 绮炬帓瀹屾垚锛氬€欓€?{}锛岃繑鍥?{}", candidates.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.warn("[Reranker] 绮炬帓澶辫触鎴栬秴鏃讹紝闄嶇骇浣跨敤 RRF 鍒嗘暟锛歿}", e.getMessage());
            return candidates.stream()
                    .limit(topN)
                    .collect(Collectors.toList());
        }
    }

    private List<HybridRetrieverService.ScoredChunk> callRerankApi(
            String question,
            List<HybridRetrieverService.ScoredChunk> candidates,
            int topN) {
        List<String> docs = candidates.stream()
                .map(HybridRetrieverService.ScoredChunk::content)
                .collect(Collectors.toList());

        RerankRequest request = new RerankRequest();
        request.setModel(model);
        request.setInput(new RerankInput(question, docs));
        request.setParameters(new RerankParams(topN, false));

        WebClient client = webClientBuilder
                .baseUrl(endpoint)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        RerankResponse response = client.post()
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();

        if (response == null || response.getOutput() == null || response.getOutput().getResults() == null) {
            throw new RuntimeException("Reranker API returned an empty response");
        }

        if (response.getUsage() != null && response.getUsage().getTotalTokens() > 0) {
            tokenMetrics.recordContextTokens(response.getUsage().getTotalTokens());
        }

        return response.getOutput().getResults().stream()
                .sorted(Comparator.comparingDouble(RerankResult::getRelevanceScore).reversed())
                .map(r -> {
                    HybridRetrieverService.ScoredChunk original = candidates.get(r.getIndex());

                    return new HybridRetrieverService.ScoredChunk(
                            original.chunk(),
                            r.getRelevanceScore()
                    );
                })
                .collect(Collectors.toList());
    }
/**
 * 发送给重排服务的请求体。
 */

    @Data
    static class RerankRequest {
        private String model;

        private RerankInput input;

        private RerankParams parameters;
    }
/**
 * 重排请求中的单条候选文本。
 */

    @Data
    static class RerankInput {
        private String query;

        private List<String> documents;

        RerankInput(String query, List<String> documents) {
            this.query = query;
            this.documents = documents;
        }
    }
/**
 * 重排服务的参数设置。
 */

    @Data
    static class RerankParams {
        @JsonProperty("top_n")
        private int topN;

        @JsonProperty("return_documents")
        private boolean returnDocuments;

        RerankParams(int topN, boolean returnDocuments) {
            this.topN = topN;
            this.returnDocuments = returnDocuments;
        }
    }
/**
 * 重排服务返回的完整响应。
 */

    @Data
    static class RerankResponse {
        private RerankOutput output;

        private RerankUsage usage;
    }
/**
 * 重排服务响应中的输出部分。
 */

    @Data
    static class RerankOutput {
        private List<RerankResult> results;
    }
/**
 * 单条候选文本的重排结果。
 */

    @Data
    static class RerankResult {
        private int index;

        @JsonProperty("relevance_score")
        private double relevanceScore;
    }
/**
 * 重排服务返回的 token 用量信息。
 */

    @Data
    static class RerankUsage {
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
