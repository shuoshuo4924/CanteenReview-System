package org.example.canteenreview.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * CSRF token 校验（手写版，不引入 Spring Security）。
 *
 * <p>为什么在 Origin 校验之外还要 token：
 * {@link OriginCheckInterceptor} 对"没有 Origin/Referer 的请求"是放行的（为了脚本和健康检查），
 * 也就是说非浏览器客户端可以绕过它。token 是唯一无法被跨站伪造的东西：
 * 攻击者的页面读不到本站页面里的 token，也不知道 session 里的值。
 *
 * <p>token 存在 Session 里，整个会话内不变（不做一次性轮换）：
 * 轮换会让用户开两个标签页时，后提交的那个永远失败。
 *
 * <p>取值顺序：先看请求头 {@code X-CSRF-TOKEN}（页面里的 fetch 用），
 * 再看表单字段 {@code _csrf}（普通表单用）。比较用 {@link MessageDigest#isEqual} 常量时间，
 * 避免通过响应时间逐字节猜 token。
 *
 * <p>失败时：AJAX 请求回 JSON（前端能提示"刷新页面"），普通表单回 403 页面。
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CsrfInterceptor.class);

    /** Session 里存 token 的 key，模板通过 ${session.csrfToken} 读取 */
    public static final String SESSION_ATTR = "csrfToken";

    private static final String PARAM = "_csrf";
    private static final String HEADER = "X-CSRF-TOKEN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 页面渲染时要能读到 token，所以先确保它存在
        HttpSession session = request.getSession(true);
        String expected = (String) session.getAttribute(SESSION_ATTR);
        if (expected == null) {
            expected = UUID.randomUUID().toString().replace("-", "");
            session.setAttribute(SESSION_ATTR, expected);
        }

        if (!isWriteMethod(request.getMethod())) {
            return true;
        }

        String submitted = request.getHeader(HEADER);
        if (submitted == null || submitted.isBlank()) {
            submitted = request.getParameter(PARAM);
        }
        if (matches(expected, submitted)) {
            return true;
        }

        log.warn("CSRF token 校验失败：method={}, uri={}, ip={}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        reject(request, response);
        return false;
    }

    private static boolean isWriteMethod(String method) {
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
    }

    /** 常量时间比较，长度不同也走完整比较流程 */
    private static boolean matches(String expected, String submitted) {
        if (submitted == null || submitted.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                submitted.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        if (isAjax(request)) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"❌ 页面已过期，刷新一下再提交吧\"}");
            return;
        }
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<h3>403 请求被拒绝</h3>"
                + "<p>页面可能已经过期，返回上一页刷新后重试。</p>");
    }

    private static boolean isAjax(HttpServletRequest request) {
        if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("application/json")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json");
    }
}
