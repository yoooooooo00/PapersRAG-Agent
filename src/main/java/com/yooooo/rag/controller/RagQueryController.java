package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.dto.RagQueryRequest;
import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.exception.BizException;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.permission.PermissionService;
import com.yooooo.rag.service.rag.FullRagPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供 RAG 问答入口，把用户问题交给完整检索生成流程处理。
 */
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Slf4j
public class RagQueryController {
    private final FullRagPipeline fullRagPipeline;
    private final PermissionService permissionService;

    @PostMapping("/query")
    public ApiResponse<RagResponse> query(@RequestBody RagQueryRequest req) {
        validateRequest(req);
        req.getKbIds().forEach(permissionService::requireRead);

        long start = System.currentTimeMillis();
        log.info("[RAG接口] 收到查询请求 userId={} kbIds={} question={}",
                UserContext.getUserId(), req.getKbIds(), preview(req.getQuestion()));
        RagResponse response = fullRagPipeline.query(req.getQuestion(), req.getKbIds());
        log.info("[RAG接口] 查询完成 userId={} kbIds={} notFound={} sources={} elapsed={}ms",
                UserContext.getUserId(), req.getKbIds(), response.isNotFound(),
                response.getSources() != null ? response.getSources().size() : 0,
                System.currentTimeMillis() - start);
        return ApiResponse.ok(response);
    }

    private void validateRequest(RagQueryRequest req) {
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw BizException.badRequest("Question must not be empty");
        }
        if (req.getKbIds() == null || req.getKbIds().isEmpty()) {
            throw BizException.badRequest("Knowledge bases must not be empty");
        }
    }

    private String preview(String question) {
        if (question == null) {
            return "";
        }
        return question.substring(0, Math.min(60, question.length()));
    }
}
