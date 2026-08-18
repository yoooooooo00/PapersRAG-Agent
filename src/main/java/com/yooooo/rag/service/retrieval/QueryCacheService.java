package com.yooooo.rag.service.retrieval;

import com.yooooo.rag.dto.RagResponse;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 缓存查询结果和嵌入向量，减少重复计算和外部调用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryCacheService {
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "rag:query:";
    private static final Duration QUERY_TTL = Duration.ofMinutes(10);

    @Value("${rag.cache.query-enabled:false}")
    private boolean queryCacheEnabled;

    public RagResponse getFromCache(String question, List<Long> kbIds) {
        if (!queryCacheEnabled) {
            return null;
        }
        String key = buildKey(question, kbIds);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof RagResponse resp) {
            log.info("[QueryCache] 命中缓存：question={}", question.substring(0, Math.min(30, question.length())));
            return resp;
        }
        return null;
    }

    public void putToCache(String question, List<Long> kbIds, RagResponse response) {
        if (!queryCacheEnabled) {
            return;
        }
        if (response.isNotFound()) return;

        String key = buildKey(question, kbIds);
        redisTemplate.opsForValue().set(key, response, QUERY_TTL);
        log.debug("[QueryCache] 写入缓存：key={}", key);
    }
    private String buildKey(String question, List<Long> kbIds) {
        List<Long> sortedIds = kbIds.stream().sorted().toList();
        return CACHE_PREFIX + toMd5(question + ":" + sortedIds);
    }

    private String toMd5(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
