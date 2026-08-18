package com.yooooo.rag.service.feedback;

import com.yooooo.rag.entity.*;
import com.yooooo.rag.entity.AnswerFeedback;
import com.yooooo.rag.entity.ChatMessage;
import com.yooooo.rag.repository.*;
import com.yooooo.rag.repository.AnswerFeedbackRepository;
import com.yooooo.rag.repository.ChatMessageRepository;
import com.yooooo.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存和更新用户反馈，并把反馈关联到具体聊天消息。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackService {
    private final AnswerFeedbackRepository feedbackRepository;
    private final ChatMessageRepository messageRepository;

    @Transactional
    public void submitFeedback(Long messageId, int feedback, String comment) {
        ChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));

        Long userId = UserContext.getUserId();
        AnswerFeedback fb = feedbackRepository.findByMessageIdAndUserId(messageId, userId)
                .orElseGet(() -> {
                    AnswerFeedback newFb = new AnswerFeedback();
                    newFb.setMessageId(messageId);
                    newFb.setUserId(userId);
                    return newFb;
                });
        fb.setFeedback((short) feedback);
        fb.setComment(comment);
        feedbackRepository.save(fb);

        message.setFeedback((short) feedback);
        messageRepository.save(message);

        if (feedback == -1) {
            log.info("[Feedback] 差评记录，候选加入评估集：messageId={}", messageId);
        }

        log.info("[Feedback] 反馈已记录：messageId={}，feedback={}，userId={}",
                messageId, feedback, UserContext.getUserId());
    }
}
