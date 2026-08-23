package com.yooooo.rag.service.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds bilingual PostgreSQL full-text queries for an English academic corpus.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TsQueryBuilder {
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)[\\p{L}\\p{N}]+(?:[-_][\\p{L}\\p{N}]+)*");
    private static final int MAX_TERMS = 12;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "what", "how", "why", "which",
            "paper", "question", "please", "tell", "about", "method",
            "name", "full", "answer", "thing", "things", "论文", "问题");

    private final ChatClient chatClient;

    @Value("${rag.retrieval.llm-query-expansion-enabled:true}")
    private boolean llmQueryExpansionEnabled;

    public String build(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        Set<String> keywords = extractLatinTerms(query);
        if (llmQueryExpansionEnabled) {
            keywords.addAll(extractLatinTerms(expandToEnglishTerms(query)));
        }

        if (keywords.isEmpty()) {
            log.debug("[TsQuery] no English terms extracted; skip full-text query={}", query);
            return null;
        }

        String tsQuery = keywords.stream()
                .limit(MAX_TERMS)
                .collect(Collectors.joining(" | "));
        log.debug("[TsQuery] query='{}' tsQuery='{}'", query, tsQuery);
        return tsQuery;
    }

    private Set<String> extractLatinTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().strip();
            String normalized = token.toLowerCase(Locale.ROOT);
            if (normalized.codePoints().anyMatch(cp -> cp > 127)) {
                continue;
            }
            if (normalized.length() < 2 || STOP_WORDS.contains(normalized)) {
                continue;
            }
            terms.add(normalized);
        }
        return terms;
    }

    private String expandToEnglishTerms(String query) {
        try {
            String prompt = """
                    Extract concise English search terms for an English academic paper.
                    Return only one term or short phrase per line, with no numbering or explanation.
                    Preserve exact acronyms, names, datasets, and hyphenated terms.
                    Translate Chinese concepts into likely English paper terms.
                    Return at most 8 lines.
                    User question: %s
                    """.formatted(query);

            String expanded = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            log.debug("[TsQuery] LLM English expansion query='{}' expansion='{}'", query, expanded);
            return expanded;
        } catch (Exception e) {
            log.debug("[TsQuery] LLM expansion failed, using original terms: {}", e.getMessage());
            return "";
        }
    }
}
