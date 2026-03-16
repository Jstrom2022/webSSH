package com.webssh.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局 REST API 异常处理器，统一捕获并转换业务层抛出的异常为 HTTP 响应。
 * <p>
 * 使用 {@code @RestControllerAdvice} 使该类对所有 {@code @RestController} 生效，
 * 避免在每个控制器中重复 try-catch。将异常映射为统一的 JSON 格式（message 字段），
 * 便于前端统一解析和展示错误信息。
 * </p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 处理参数非法异常（IllegalArgumentException）。
     * <p>
     * 通常由业务校验失败触发，如 ID 格式错误、必填项为空等。返回 400 Bad Request 表示
     * 客户端请求有问题，需要修正请求参数后重试。
     * </p>
     *
     * @param e 业务层抛出的 IllegalArgumentException
     * @return HTTP 400 响应，body 为 {@code { "message": "错误描述" }}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        // 统一 message 字段，前端可直接 toast 展示。
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
    }

    /**
     * 处理状态非法异常（IllegalStateException）。
     * <p>
     * 通常表示业务逻辑前置条件不满足，如"会话已存在"、"资源已被删除"等。返回 500 而非 400，
     * 是因为这类错误多由并发或状态不一致引起，客户端无法通过修改请求参数解决。
     * </p>
     *
     * @param e 业务层抛出的 IllegalStateException
     * @return HTTP 500 响应，body 为 {@code { "message": "错误描述" }}
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        // 保留异常文本但不透传堆栈，避免泄露内部实现细节。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
    }
}
