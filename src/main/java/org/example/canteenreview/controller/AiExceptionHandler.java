package org.example.canteenreview.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * AI 接口专属的异常兜底。
 *
 * 只作用于 {@link AiController}：这些接口给前端 fetch 用，约定返回 {"reply": "..."}，
 * 一旦抛异常被通用处理器接走，前端 res.json() 就会解析失败，用户看到的是"网络开小差"，
 * 真正的原因反而被掩盖。
 */
@RestControllerAdvice(assignableTypes = AiController.class)
public class AiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAiException(Exception e) {
        log.error("AI 接口异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("reply", "AI 服务出了点问题，稍后再试一次吧"));
    }
}
