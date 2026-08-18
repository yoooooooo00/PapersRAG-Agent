package com.yooooo.rag.service.indexing;

import com.yooooo.rag.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 后台拉取待处理索引任务并调度执行。
 */
@Component
@Slf4j
public class IndexTaskLauncher {
    private final IndexService indexService;

    public IndexTaskLauncher(@Lazy IndexService indexService) {
        this.indexService = indexService;
    }

    @Async("indexTaskExecutor")
    public void launchFromMinio(Long taskId, Long docId,
                                Long userId, String departmentId, String role) {
        long start = System.currentTimeMillis();
        log.info("[索引任务] 从 MinIO 执行任务开始 taskId={} docId={} userId={} deptId={} role={}",
                taskId, docId, userId, departmentId, role);
        UserContext.set(userId, departmentId, role);
        try {
            indexService.executeFromMinio(taskId, docId);
            log.info("[索引任务] 从 MinIO 执行任务完成 taskId={} docId={} elapsed={}ms",
                    taskId, docId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[索引任务] 从 MinIO 执行任务失败 taskId={} docId={} elapsed={}ms reason={}",
                    taskId, docId, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        } finally {
            UserContext.clear();
        }
    }

    @Async("indexTaskExecutor")
    public void launchWithText(Long taskId, Long docId, String textContent,
                               Long userId, String departmentId, String role) {
        long start = System.currentTimeMillis();
        log.info("[索引任务] 从文本执行任务开始 taskId={} docId={} userId={} deptId={} role={} textLength={}",
                taskId, docId, userId, departmentId, role, textContent != null ? textContent.length() : 0);
        UserContext.set(userId, departmentId, role);
        try {
            indexService.executeWithText(taskId, docId, textContent);
            log.info("[索引任务] 从文本执行任务完成 taskId={} docId={} elapsed={}ms",
                    taskId, docId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[索引任务] 从文本执行任务失败 taskId={} docId={} elapsed={}ms reason={}",
                    taskId, docId, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        } finally {
            UserContext.clear();
        }
    }
}
