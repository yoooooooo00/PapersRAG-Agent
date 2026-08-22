package com.yooooo.rag.service.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async launcher for evaluation tasks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EvalTaskLauncher {
    private final @Lazy EvalService evalService;

    @Async
    public void launch(Long taskId, Long kbId, String evalVersion) {
        log.info("[EvalTask] launch taskId={} kbId={} version={}", taskId, kbId, evalVersion);
        evalService.executeAsync(taskId, kbId, evalVersion);
    }
}
