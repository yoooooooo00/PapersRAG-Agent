package com.yooooo.rag.service.metrics;

import com.yooooo.rag.security.UserContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Token 用量统计组件。
 *
 * <p>Redis 中按时间粗细拆成三层：
 * 1. 近 7 天保留小时级明细，key 为「用户 + 小时」，Hash 字段为各类 token 埋点。
 * 2. 7 到 30 天保留天级总量，key 为「用户 + 日期」，String 保存当天总 token 数。
 * 3. 30 天以前合并到永久总计数器，key 为「用户 + total」。
 *
 * <p>小时桶和日桶通过 TTL 自动清理，定时压缩任务只负责把即将降级的数据汇总到下一层。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenMetrics {
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate stringRedisTemplate;

    /** Redis key 命名空间，所有 token 统计数据都放在该前缀下。 */
    private static final String REDIS_KEY_PREFIX = "rag:token-stats";

    /** 记录出现过统计数据的用户，用于定时任务遍历需要压缩的用户。 */
    private static final String USERS_KEY = REDIS_KEY_PREFIX + ":users";

    /** 小时桶保留 8 天，比展示窗口多 1 天，给小时到日的压缩任务留缓冲。 */
    private static final Duration HOURLY_TTL = Duration.ofDays(8);

    /** 日桶保留 31 天，比 30 天窗口多 1 天，给日到总量的压缩任务留缓冲。 */
    private static final Duration DAILY_TTL = Duration.ofDays(31);

    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final String EMBEDDING_FIELD = "embeddingTokens";
    private static final String CONTEXT_FIELD = "contextTokens";
    private static final String GENERATION_FIELD = "generationTokens";

    private Counter embeddingTokenCounter;
    private Counter contextTokenCounter;
    private Counter generationTokenCounter;

    /** 初始化 Micrometer 指标，用于应用内监控；Redis 负责可查询的持久统计。 */
    @PostConstruct
    public void init() {
        embeddingTokenCounter = Counter.builder("rag.tokens.embedding")
                .description("Embedding tokens consumed")
                .register(meterRegistry);

        contextTokenCounter = Counter.builder("rag.tokens.context")
                .description("Context tokens sent to the model")
                .register(meterRegistry);

        generationTokenCounter = Counter.builder("rag.tokens.generation")
                .description("Generation tokens produced by the model")
                .register(meterRegistry);
    }

    /** 记录向量化消耗的 token。 */
    public void recordEmbeddingTokens(int tokens) {
        log.info("[TokenMetrics] recordEmbeddingTokens={}", tokens);
        embeddingTokenCounter.increment(tokens);
        incrementRedis(EMBEDDING_FIELD, tokens);
    }

    /** 记录送入模型上下文的 token。 */
    public void recordContextTokens(int tokens) {
        log.info("[TokenMetrics] recordContextTokens={}", tokens);
        contextTokenCounter.increment(tokens);
        incrementRedis(CONTEXT_FIELD, tokens);
    }

    /** 记录模型生成结果消耗的 token。 */
    public void recordGenerationTokens(int tokens) {
        log.info("[TokenMetrics] recordGenerationTokens={}", tokens);
        generationTokenCounter.increment(tokens);
        incrementRedis(GENERATION_FIELD, tokens);
    }

    /**
     * 查询用户近 7 天某个埋点的小时级汇总。
     *
     * <p>7 天以后会降级为天级总量，不再保留字段拆分，因此这个方法只返回细粒度窗口内的数据。
     */
    public long getUserTokens(Long userId, String field) {
        return sumHourlyField(userId, field);
    }

    /**
     * 查询用户 token 汇总数据。
     *
     * <p>返回值中 embedding/context/generation 三个字段只表示近 7 天明细；
     * historicalTokens 表示 7 天以前的粗粒度汇总；totalTokens 是三层合计。
     */
    public Map<String, Object> getUserTokenStats(Long userId) {
        long embeddingTokens = getUserTokens(userId, EMBEDDING_FIELD);
        long contextTokens = getUserTokens(userId, CONTEXT_FIELD);
        long generationTokens = getUserTokens(userId, GENERATION_FIELD);
        long recent7DayTokens = embeddingTokens + contextTokens + generationTokens;
        long middleWindowTokens = sumDailyWindow(userId);
        long archivedTokens = parseLong(stringRedisTemplate.opsForValue().get(totalKey(userId)));
        long historicalTokens = middleWindowTokens + archivedTokens;

        Map<String, Object> stats = new HashMap<>();
        stats.put(EMBEDDING_FIELD, embeddingTokens);
        stats.put(CONTEXT_FIELD, contextTokens);
        stats.put(GENERATION_FIELD, generationTokens);
        stats.put("recent7DayTokens", recent7DayTokens);
        stats.put("middleWindowTokens", middleWindowTokens);
        stats.put("archivedTokens", archivedTokens);
        stats.put("historicalTokens", historicalTokens);
        stats.put("totalTokens", recent7DayTokens + historicalTokens);
        return stats;
    }

    /**
     * 每小时压缩上一小时的封口桶。
     *
     * <p>小时桶是 Hash，保存三个埋点的明细；压缩时只把 Hash 所有字段求和后写入当天日桶。
     * markerKey 使用 setIfAbsent 做幂等保护，避免应用重启或多实例调度导致重复累加。
     */
    @Scheduled(cron = "0 5 * * * *")
    public void compactPreviousHour() {
        LocalDateTime hour = LocalDateTime.now(ZONE_ID).minusHours(1).withMinute(0).withSecond(0).withNano(0);
        Set<String> users = stringRedisTemplate.opsForSet().members(USERS_KEY);
        if (users == null || users.isEmpty()) {
            return;
        }

        for (String user : users) {
            Long userId = parseUserId(user);
            if (userId == null) {
                continue;
            }

            String hourKey = hourlyKey(userId, hour);
            long total = sumHashValues(hourKey);
            if (total <= 0) {
                continue;
            }

            String markerKey = hourKey + ":compacted";
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(markerKey, "1", HOURLY_TTL);
            if (Boolean.TRUE.equals(locked)) {
                String dayKey = dailyKey(userId, hour.toLocalDate());
                stringRedisTemplate.opsForValue().increment(dayKey, total);
                stringRedisTemplate.expire(dayKey, DAILY_TTL);
            }
        }
    }

    /**
     * 每天把 30 天前的日桶压缩进永久总计数器。
     *
     * <p>日桶仍保留到 TTL 到期自动删除；这里不主动删 key，降低任务失败或并发执行时的数据风险。
     */
    @Scheduled(cron = "0 15 0 * * *")
    public void compactExpiredDailyBucket() {
        LocalDate date = LocalDate.now(ZONE_ID).minusDays(30);
        Set<String> users = stringRedisTemplate.opsForSet().members(USERS_KEY);
        if (users == null || users.isEmpty()) {
            return;
        }

        for (String user : users) {
            Long userId = parseUserId(user);
            if (userId == null) {
                continue;
            }

            String dayKey = dailyKey(userId, date);
            long total = parseLong(stringRedisTemplate.opsForValue().get(dayKey));
            if (total <= 0) {
                continue;
            }

            String markerKey = dailyCompactMarkerKey(userId, date);
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(markerKey, "1", DAILY_TTL);
            if (Boolean.TRUE.equals(locked)) {
                stringRedisTemplate.opsForValue().increment(totalKey(userId), total);
            }
        }
    }

    /** 将一次 token 增量写入当前小时桶，并刷新小时桶 TTL。 */
    private void incrementRedis(String field, int delta) {
        try {
            Long userId = UserContext.getUserId();
            String key = hourlyKey(userId, LocalDateTime.now(ZONE_ID));
            log.info("[TokenMetrics] incrementRedis: key={}, field={}, delta={}", key, field, delta);

            stringRedisTemplate.opsForHash().increment(key, field, delta);
            stringRedisTemplate.expire(key, HOURLY_TTL);
            stringRedisTemplate.opsForSet().add(USERS_KEY, String.valueOf(userId));
        } catch (Exception e) {
            log.error("[TokenMetrics] Redis write failed: {}", e.getMessage(), e);
        }
    }

    /** 遍历近 7 天所有小时桶，汇总指定埋点字段。 */
    private long sumHourlyField(Long userId, String field) {
        LocalDateTime now = LocalDateTime.now(ZONE_ID).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime start = now.minusDays(7);
        long total = 0;

        for (LocalDateTime hour = start; !hour.isAfter(now); hour = hour.plusHours(1)) {
            total += parseLong((String) stringRedisTemplate.opsForHash().get(hourlyKey(userId, hour), field));
        }

        log.info("[TokenMetrics] sumHourlyField: userId={}, field={}, total={}", userId, field, total);
        return total;
    }

    /** 汇总 7 到 30 天之间尚未进入永久总计数器的日桶。 */
    private long sumDailyWindow(Long userId) {
        LocalDate now = LocalDate.now(ZONE_ID);
        LocalDate start = now.minusDays(30);
        LocalDate end = now.minusDays(7);
        long total = 0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(dailyCompactMarkerKey(userId, date)))) {
                continue;
            }
            total += parseLong(stringRedisTemplate.opsForValue().get(dailyKey(userId, date)));
        }

        return total;
    }

    /** 汇总小时桶 Hash 中所有埋点字段。 */
    private long sumHashValues(String key) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash().entries(key);
        return values.values().stream()
                .map(String.class::cast)
                .mapToLong(this::parseLong)
                .sum();
    }

    /** 构造小时桶 key：rag:token-stats:{userId}:hour:{yyyyMMddHH}。 */
    private String hourlyKey(Long userId, LocalDateTime hour) {
        LocalDateTime normalizedHour = hour.withMinute(0).withSecond(0).withNano(0);
        return REDIS_KEY_PREFIX + ":" + userId + ":hour:" + HOUR_FORMATTER.format(normalizedHour);
    }

    /** 构造日桶 key：rag:token-stats:{userId}:day:{yyyyMMdd}。 */
    private String dailyKey(Long userId, LocalDate date) {
        return REDIS_KEY_PREFIX + ":" + userId + ":day:" + DAY_FORMATTER.format(date);
    }

    /** 构造永久总量 key：rag:token-stats:{userId}:total。 */
    private String totalKey(Long userId) {
        return REDIS_KEY_PREFIX + ":" + userId + ":total";
    }

    /** 构造日桶压缩标记 key，用于避免同一天重复归档。 */
    private String dailyCompactMarkerKey(Long userId, LocalDate date) {
        return dailyKey(userId, date) + ":compacted";
    }

    /** 解析用户集合中的用户 ID，遇到脏数据时跳过该用户。 */
    private Long parseUserId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("[TokenMetrics] invalid user id in stats set: {}", value);
            return null;
        }
    }

    /** Redis 中缺失值或非法数字都按 0 处理，保证统计接口稳定返回。 */
    private long parseLong(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}