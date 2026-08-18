package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.metrics.TokenMetrics;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provides statistics endpoints.
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {
    private final TokenMetrics tokenMetrics;

    @GetMapping("/tokens")
    public ApiResponse<Map<String, Object>> getTokenStats() {
        Long userId = UserContext.getUserId();
        Map<String, Object> stats = tokenMetrics.getUserTokenStats(userId);

        long embeddingTokens = (long) stats.get("embeddingTokens");
        long contextTokens = (long) stats.get("contextTokens");
        long generationTokens = (long) stats.get("generationTokens");

        double estimatedCostCny =
                embeddingTokens / 1000.0 * 0.0007
                + contextTokens / 1000.0 * 0.0008
                + generationTokens / 1000.0 * 0.002;
        stats.put("estimatedCostCny", Math.round(estimatedCostCny * 10000) / 10000.0);

        return ApiResponse.ok(stats);
    }
}
