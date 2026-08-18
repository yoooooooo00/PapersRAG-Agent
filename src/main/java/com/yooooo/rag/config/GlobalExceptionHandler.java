package com.yooooo.rag.config;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一处理业务异常和系统异常，保证接口返回格式一致。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        log.warn("[业务异常] code={}，message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[系统异常] {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(500, "系统内部错误，请稍后重试"));
    }
}
