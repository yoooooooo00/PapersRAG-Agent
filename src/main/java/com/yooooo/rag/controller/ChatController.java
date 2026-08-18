package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.dto.ChatRequest;
import com.yooooo.rag.dto.RagResponse;
import com.yooooo.rag.entity.ChatMessage;
import com.yooooo.rag.entity.ChatSession;
import com.yooooo.rag.exception.BizException;
import com.yooooo.rag.repository.ChatMessageRepository;
import com.yooooo.rag.repository.ChatSessionRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.chat.ChatSessionService;
import com.yooooo.rag.service.permission.PermissionService;
import com.yooooo.rag.service.rag.StreamingRagService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 提供同步聊天和 SSE 流式聊天接口，并维护会话消息。
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {
    private final StreamingRagService streamingRagService;
    private final ChatSessionService sessionService;
    private final PermissionService permissionService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    private final AtomicInteger sseThreadCounter = new AtomicInteger(1);
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(
            r -> {
                Thread thread = new Thread(r);
                thread.setName("sse-rag-" + sseThreadCounter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
    );

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String question,
            @RequestParam List<Long> kbIds,
            @RequestParam(required = false) String sessionId) {
        kbIds.forEach(permissionService::requireRead);
        String sid = sessionService.getOrCreateSession(sessionId, kbIds);

        SseEmitter emitter = new SseEmitter(60_000L);

        Long currentUserId = UserContext.getUserId();
        String currentDeptId = UserContext.getDepartmentId();
        String currentRole = UserContext.getRole();

        sseExecutor.submit(() -> {
            UserContext.set(currentUserId, currentDeptId, currentRole);
            try {
                streamingRagService.streamQuery(question, kbIds, sid, emitter);
            } catch (Exception e) {
                log.error("[Chat] SSE 执行异常：{}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"message\":\"系统内部错误，请稍后重试\"}"));
                    emitter.complete();
                } catch (IOException ignored) {
                    log.debug("[Chat] SSE error event send failed", ignored);
                }
            } finally {
                UserContext.clear();
            }
        });

        return emitter;
    }

    @PostMapping
    public RagResponse syncChat(@RequestBody ChatRequest request) {
        validateRequest(request);
        request.getKbIds().forEach(permissionService::requireRead);
        String sid = sessionService.getOrCreateSession(request.getSessionId(), request.getKbIds());
        return streamingRagService.syncQuery(request.getQuestion(), request.getKbIds(), sid);
    }

    private void validateRequest(ChatRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw BizException.badRequest("Question must not be empty");
        }
        if (request.getKbIds() == null || request.getKbIds().isEmpty()) {
            throw BizException.badRequest("Knowledge bases must not be empty");
        }
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSession>> listSessions() {
        List<ChatSession> sessions = sessionRepository
                .findByUserIdAndIsDeletedFalseOrderByLastActiveAtDesc(UserContext.getUserId());
        return ApiResponse.ok(sessions);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessage>> getMessages(@PathVariable String sessionId) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> BizException.notFound("Session not found"));
        if (Boolean.TRUE.equals(session.getIsDeleted())) {
            throw BizException.notFound("Session not found");
        }
        if (!session.getUserId().equals(UserContext.getUserId()) && !UserContext.isAdmin()) {
            throw BizException.forbidden("No permission to access this session");
        }
        return ApiResponse.ok(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }
}
