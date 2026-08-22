package com.yooooo.rag.service.retrieval;

import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Classifies a paper QA question into a retrieval route.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryRoutingService {
    public enum QueryRoute {
        SIMPLE,
        STANDARD,
        COMPLEX
    }

    private static final Pattern COMPLEX_PATTERN = Pattern.compile(
            "(?i)(compare|comparison|difference|why|cause|reason|impact|tradeoff|ablation|versus|vs\\.|between|multi[- ]step|cross[- ]paper|overall summary)");
    private static final Pattern SIMPLE_PATTERN = Pattern.compile(
            "(?i)(what is|what are|who is|when|where|how many|how much|definition|meaning|name|author)");

    private final ChatClient chatClient;

    @Value("${rag.routing.enabled:true}")
    private boolean enabled;

    @Cacheable(value = "query-route-cache", key = "#question.hashCode()")
    public QueryRoute classify(String question) {
        if (!enabled || question == null || question.isBlank()) {
            return QueryRoute.STANDARD;
        }

        try {
            String prompt = """
                    You are classifying a user question for a literature RAG system.
                    Return only one label:
                    SIMPLE: a short factual lookup, definition, number, name, or single direct answer.
                    STANDARD: a normal paper question about method, result, dataset, section, or explanation.
                    COMPLEX: comparison, ablation, cause analysis, multi-step reasoning, or questions that need multiple paper sections.

                    User question: %s
                    """.formatted(question);

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            QueryRoute baseRoute = parse(result);
            QueryRoute route = upgradeForPaperSignals(baseRoute, question);
            log.info("[QueryRouting] baseRoute={} route={} question={}", baseRoute, route, preview(question));
            return route;
        } catch (Exception e) {
            QueryRoute fallback = heuristic(question);
            log.warn("[QueryRouting] classifier failed, fallback route={} error={}", fallback, e.getMessage());
            return fallback;
        }
    }

    private QueryRoute parse(String value) {
        if (value == null) {
            return QueryRoute.STANDARD;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (normalized.contains("COMPLEX")) {
            return QueryRoute.COMPLEX;
        }
        if (normalized.contains("SIMPLE")) {
            return QueryRoute.SIMPLE;
        }
        if (normalized.contains("STANDARD")) {
            return QueryRoute.STANDARD;
        }
        return QueryRoute.STANDARD;
    }

    private QueryRoute upgradeForPaperSignals(QueryRoute route, String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (COMPLEX_PATTERN.matcher(q).find()) {
            return QueryRoute.COMPLEX;
        }
        if (SIMPLE_PATTERN.matcher(q).find() && q.length() <= 40) {
            return QueryRoute.SIMPLE;
        }
        return route;
    }

    private QueryRoute heuristic(String question) {
        String q = question == null ? "" : question.strip();
        String lower = q.toLowerCase(Locale.ROOT);
        if (COMPLEX_PATTERN.matcher(lower).find() || q.length() >= 80) {
            return QueryRoute.COMPLEX;
        }
        if ((SIMPLE_PATTERN.matcher(lower).find() || q.length() <= 24) && q.length() <= 40) {
            return QueryRoute.SIMPLE;
        }
        return QueryRoute.STANDARD;
    }

    private String preview(String question) {
        if (question == null) {
            return "";
        }
        return question.substring(0, Math.min(40, question.length()));
    }
}