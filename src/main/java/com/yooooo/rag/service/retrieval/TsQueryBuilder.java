package com.yooooo.rag.service.retrieval;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把用户问题转换为 PostgreSQL 全文检索使用的 tsquery 表达式。
 */
@Component
@Slf4j
public class TsQueryBuilder {
    private static final List<String> STOP_WORDS = List.of(
            "的", "了", "是", "在", "有", "和", "与", "或", "这", "那",
            "什么", "怎么", "如何", "为什么", "哪些", "怎样", "请问",
            "a", "an", "the", "is", "are", "what", "how"
    );

    public String build(String query) {
        if (query == null || query.isBlank()) return null;
        String[] tokens = query.split("[\\s\\p{P}]+");
        List<String> keywords = Arrays.stream(tokens)
                .map(String::strip)
                .filter(t -> !t.isBlank())
                .filter(t -> t.length() >= 2)
                .filter(t -> !STOP_WORDS.contains(t.toLowerCase()))
                .collect(Collectors.toList());
        if (keywords.isEmpty()) {
            keywords = List.of(query.substring(0, Math.min(20, query.length())));
        }
        String tsQuery = String.join(" & ", keywords);
        log.debug("[TsQuery] query='{}' → tsQuery='{}'", query, tsQuery);
        return tsQuery;
    }
}
