package com.yooooo.rag.service.embedding;

import com.yooooo.rag.service.metrics.TokenMetrics;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 调用嵌入模型生成文本向量，并做基础缓存和容错处理。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate redisTemplate;
    private final TokenMetrics tokenMetrics;
    private static final String CACHE_PREFIX = "emb:v1:";

    @Value("${rag.cache.embedding-ttl:7d}")
    private Duration embeddingTtl;
    private static final int BATCH_SIZE = 10;

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        Map<Integer, float[]> cached = new HashMap<>();
        List<Integer> missedIndices = new ArrayList<>();
        List<String> missedTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String cacheKey = buildCacheKey(texts.get(i));
            String cachedStr = redisTemplate.opsForValue().get(cacheKey);
            if (cachedStr != null) {
                cached.put(i, deserializeVector(cachedStr));
            } else {
                missedIndices.add(i);
                missedTexts.add(texts.get(i));
            }
        }
        log.debug("[Embedding] 总数={}，缓存命中={}，需要调API={}",
                texts.size(), cached.size(), missedTexts.size());
        if (!missedTexts.isEmpty()) {
            List<float[]> newVectors = embedFromApi(missedTexts);
            for (int j = 0; j < missedIndices.size(); j++) {
                int originalIndex = missedIndices.get(j);
                float[] vector = newVectors.get(j);
                cached.put(originalIndex, vector);
                String cacheKey = buildCacheKey(texts.get(originalIndex));
                redisTemplate.opsForValue().set(cacheKey, serializeVector(vector), embeddingTtl);
            }
        }
        return IntStream.range(0, texts.size())
                .mapToObj(cached::get)
                .toList();
    }

    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<float[]> embedFromApi(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        AtomicInteger totalTokens = new AtomicInteger(0);

        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(start, end);
            long batchStart = System.currentTimeMillis();

            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(batch, null));
            long elapsed = System.currentTimeMillis() - batchStart;

            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                long tokens = response.getMetadata().getUsage().getTotalTokens();
                totalTokens.addAndGet((int) tokens);
            }
            response.getResults().stream()
                    .sorted(Comparator.comparingInt(r -> r.getIndex()))
                    .forEach(r -> result.add(r.getOutput()));
            log.debug("[Embedding] 批次{}/{}，size={}，耗时={}ms",
                    start / BATCH_SIZE + 1,
                    (texts.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    batch.size(), elapsed);
        }
        log.info("[Embedding] API调用完成，共{}条，消耗Token={}",
                texts.size(), totalTokens.get());
        if (totalTokens.get() > 0) {
            tokenMetrics.recordEmbeddingTokens(totalTokens.get());
        }
        return result;
    }

    @Recover
    public List<float[]> embedFromApiFallback(Exception e, List<String> texts) {
        log.error("[Embedding] 重试3次后仍失败，texts.size={}，error={}",
                texts.size(), e.getMessage());

        throw new RuntimeException("Embedding API 调用失败，已重试3次：" + e.getMessage(), e);
    }

    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    private String buildCacheKey(String text) {
        return CACHE_PREFIX + toMd5(text);
    }

    private String toMd5(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");

            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private String serializeVector(float[] vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private float[] deserializeVector(String str) {
        str = str.replace("[", "").replace("]", "").replace(" ", "");

        String[] parts = str.split(",");

        float[] vector = new float[parts.length];

        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
