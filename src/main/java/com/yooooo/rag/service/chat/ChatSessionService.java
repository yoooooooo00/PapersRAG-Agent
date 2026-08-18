package com.yooooo.rag.service.chat;

import com.yooooo.rag.entity.ChatMessage;
import com.yooooo.rag.entity.ChatSession;
import com.yooooo.rag.exception.BizException;
import com.yooooo.rag.repository.ChatMessageRepository;
import com.yooooo.rag.repository.ChatSessionRepository;
import com.yooooo.rag.security.UserContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 聊天会话服务
 * 负责聊天会话创建、会话权限校验、会话复用、聊天消息持久化、历史消息截断管理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {
    /** 最大携带历史轮数，一轮=用户提问+AI回复，最多保留5轮对话 */
    private static final int MAX_HISTORY_ROUNDS = 5;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    /**
     * 获取已有会话，不存在sessionId则新建会话
     * @param sessionId 前端传入会话ID，为空时创建新会话
     * @param kbIds 本次对话绑定的知识库ID列表
     * @return 有效会话ID
     */
    public String getOrCreateSession(String sessionId, List<Long> kbIds) {
        // 前端携带sessionId，复用已有会话
        if (sessionId != null && !sessionId.isBlank()) {
            ChatSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> BizException.notFound("Session not found"));
            // 校验会话是否已删除
            if (Boolean.TRUE.equals(session.getIsDeleted())) {
                throw BizException.notFound("Session not found");
            }
            // 权限校验：普通用户只能访问自己会话，管理员不受限制
            if (!session.getUserId().equals(UserContext.getUserId()) && !UserContext.isAdmin()) {
                throw BizException.forbidden("No permission to access this session");
            }
            // 更新会话最后活跃时间
            session.setLastActiveAt(LocalDateTime.now());
            sessionRepository.save(session);
            return sessionId;
        }

        // 无会话ID，创建全新聊天会话
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(UserContext.getUserId());
        session.setKbIds(kbIds.toString());
        session.setMessageCount(0);
        sessionRepository.save(session);

        log.info("[ChatSession] new session: sessionId={}, userId={}",
                session.getId(), UserContext.getUserId());

        return session.getId();
    }

    /**
     * 保存一轮对话消息（用户提问 + AI回答），同步更新会话信息
     * @param sessionId 会话ID
     * @param question 用户提问内容
     * @param answer AI回复内容
     * @param sources 引用知识库片段来源信息
     * @param latencyMs 问答耗时（毫秒）
     */
    public void saveMessage(String sessionId, String question, String answer, String sources, int latencyMs) {
        // 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("USER");
        userMessage.setContent(question);
        messageRepository.save(userMessage);

        // 保存AI助手消息
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(sessionId);
        assistantMessage.setRole("ASSISTANT");
        assistantMessage.setContent(answer);
        assistantMessage.setSources(sources);
        assistantMessage.setLatencyMs(latencyMs);
        messageRepository.save(assistantMessage);

        // 更新会话消息数量、活跃时间，首次对话自动生成会话标题
        sessionRepository.findById(sessionId).ifPresent(session -> {
            // 一轮对话新增两条消息，计数+2
            session.setMessageCount(session.getMessageCount() + 2);
            session.setLastActiveAt(LocalDateTime.now());
            // 会话无标题时，截取前50字符提问作为会话标题
            if (session.getTitle() == null && question.length() > 0) {
                session.setTitle(question.substring(0, Math.min(50, question.length())));
            }
            sessionRepository.save(session);
        });
    }

    /**
     * 获取会话历史消息，并自动截断超限历史，控制上下文长度
     * @param sessionId 会话ID
     * @return 按创建时间升序排列的消息列表
     */
    public List<ChatMessage> getHistory(String sessionId) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        // 最大消息条数 = 最大轮数 × 2（用户+助手）
        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        // 消息超出上限，只保留最新N轮对话
        if (messages.size() > maxMessages) {
            return messages.subList(messages.size() - maxMessages, messages.size());
        }
        return messages;
    }
}