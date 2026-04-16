package com.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 全局拦截 RuntimeException
     * 将我们自定义的报错信息精准吐给前端
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        // 可以在控制台打印一下，方便排查
        log.warn("触发业务异常拦截: {}", e.getMessage());

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("status", 500);
        // 【核心】：把异常的真实文字塞进 "message" 字段
        responseBody.put("message", e.getMessage());

        // 返回 500 状态码，并携带包含 message 的 JSON
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
    }
}