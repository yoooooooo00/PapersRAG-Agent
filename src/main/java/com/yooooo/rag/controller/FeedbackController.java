package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.service.feedback.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 接收用户对回答质量的反馈，供后续评估和优化使用。
 */
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {
    private final FeedbackService feedbackService;

    @PostMapping("/{messageId}")
    public ApiResponse<Void> submitFeedback(
            @PathVariable Long messageId,
            @RequestParam int feedback,
            @RequestParam(required = false) String comment) {
        feedbackService.submitFeedback(messageId, feedback, comment);
        return ApiResponse.ok(null);
    }
}
