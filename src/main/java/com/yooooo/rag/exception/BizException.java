package com.yooooo.rag.exception;

import lombok.Getter;

/**
 * 业务异常类型，用于携带明确的 HTTP 状态码和错误消息。
 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }
}
