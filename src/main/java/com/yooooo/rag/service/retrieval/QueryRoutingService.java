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
 * 判断问题复杂度，并选择简单、标准或复杂检索路线。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryRoutingService {
/**
 * 问题路由类型，用于决定检索流程的复杂度。
 */

    public enum QueryRoute {
        SIMPLE,
        STANDARD,
        COMPLEX
    }

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
                    请将用户的知识库问题严格分类为一个标签。

                    SIMPLE：问题只询问单个事实、定义、数值、名称、时间、状态，或只需要简短直接回答。
                    示例：员工年假有几天？
                    示例：API 限流是多少？

                    STANDARD：问题是常规的操作方法、列表、规则、制度、流程说明或一般解释，通常只需要正常检索即可回答。
                    示例：新员工入职第一天需要完成哪些事项？
                    示例：代码提交规范有哪些要求？

                    COMPLEX：问题需要比较、原因分析、多步推理、跨多个文档综合、方案设计、诊断排查、权衡取舍或较宽泛的分析。
                    示例：对比 HR 制度和技术规范中对员工日常工作的要求，它们分别关注哪些方面？
                    示例：如果员工反馈系统接口调用失败，结合技术规范分析可能原因，并给出排查步骤。

                    如果问题包含入职时长、工作年限、服务期限、试用期、合同期限、有效期、间隔天数等时间条件，并且需要把这些条件换算或映射到文档规则中，先按语义分类，系统会自动将结果升一级，最高到 COMPLEX。

                    只能返回一个大写标签：SIMPLE、STANDARD 或 COMPLEX。
                    不要解释，不要输出其他内容。

                    用户问题：%s
                    """.formatted(question);

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            QueryRoute baseRoute = parse(result);
            QueryRoute route = upgradeForTimeCalculation(baseRoute, question);
            log.info("[QueryRouting] baseRoute={} route={} question={}", baseRoute, route, preview(question));
            return route;
        } catch (Exception e) {
            QueryRoute fallback = upgradeForTimeCalculation(heuristic(question), question);
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

    private QueryRoute upgradeForTimeCalculation(QueryRoute route, String question) {
        if (question == null || !TIME_CALCULATION_PATTERN.matcher(question).find()) {
            return route;
        }
        return switch (route) {
            case SIMPLE -> QueryRoute.STANDARD;
            case STANDARD, COMPLEX -> QueryRoute.COMPLEX;
        };
    }

    private QueryRoute heuristic(String question) {
        String q = question.strip();
        if (q.length() <= 24 && !COMPLEX_PATTERN.matcher(q).find()) {
            return QueryRoute.SIMPLE;
        }
        if (q.length() >= 80 || COMPLEX_PATTERN.matcher(q).find()) {
            return QueryRoute.COMPLEX;
        }
        return QueryRoute.STANDARD;
    }

    private String preview(String question) {
        return question.substring(0, Math.min(40, question.length()));
    }

    private static final Pattern TIME_CALCULATION_PATTERN = Pattern.compile(
            "入职.*(月|年|天)|工作.*(月|年|天)|在职.*(月|年|天)|服务.*(月|年|天)|满.*(月|年|天)|未满.*(月|年|天)|不足.*(月|年|天)|超过.*(月|年|天)|试用期|合同.*(期限|到期)|有效期|间隔.*(天|月|年)|距.*(天|月|年)|多久.*(年假|假期|权限|资格)|几天年假|年限|工龄|司龄|\\d+\\s*(天|个月|月|年|小时)"
    );

    private static final Pattern COMPLEX_PATTERN = Pattern.compile(
            "对比|比较|区别|为什么|原因|影响|分析|诊断|排查|方案|设计|规划|总结|综合|多个|多篇|优缺点|权衡|流程|步骤|详细"
    );
}
