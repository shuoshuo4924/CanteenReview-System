package org.example.canteenreview.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 页面请求的兜底异常处理。
 *
 * 没有它的时候，任何未捕获异常（表单字段为 null 造成的 NPE、数据库连接失败等）
 * 都会落到 Tomcat 的默认错误页：用户只看到白页，开发时也拿不到上下文线索。
 * 这里统一渲染 templates/error.html，并把堆栈按 ERROR 级别打进日志。
 *
 * 注意：/ai/** 返回 JSON，由 {@link org.example.canteenreview.controller.AiExceptionHandler} 处理，
 * 否则前端 fetch 拿到 HTML 会在 res.json() 处抛错，提示反而更难看。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 静态资源 / 不存在的地址。
     * Spring Boot 3.2+ 对找不到的资源抛这个异常，属于正常的 404，
     * 不能当 500 处理，也不能刷 ERROR 日志（favicon.ico 之类的请求很频繁）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ModelAndView handleNotFound(HttpServletRequest request, NoResourceFoundException e) {
        log.debug("资源不存在：{}", request.getRequestURI());
        return errorView(HttpStatus.NOT_FOUND, 404, "这个页面找不到了", request);
    }

    /** 其余所有未捕获异常：记日志 + 渲染友好错误页 */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleException(HttpServletRequest request, Exception e) {
        log.error("页面请求异常：{} {}", request.getMethod(), request.getRequestURI(), e);
        return errorView(HttpStatus.INTERNAL_SERVER_ERROR, 500,
                "服务器开了点小差，请稍后再试", request);
    }

    /** 统一构造错误页：变量名与 Spring Boot 自带错误页的 model 保持一致，模板两处都能用 */
    private ModelAndView errorView(HttpStatus status, int code, String message, HttpServletRequest request) {
        ModelAndView mv = new ModelAndView("error");
        mv.setStatus(status);
        mv.addObject("status", code);
        mv.addObject("error", message);
        mv.addObject("message", message);
        mv.addObject("path", request.getRequestURI());
        return mv;
    }
}
