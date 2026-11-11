package org.example.canteenreview.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;

/**
 * 跨站写操作防护（轻量版 CSRF）。
 *
 * 全站是表单 + fetch，没有引入 Spring Security，所以用最小成本补一道：
 * 浏览器发起表单提交时一定会带 Origin（旧浏览器至少带 Referer），
 * 只要来源不是本站就拒绝，能挡住"诱导已登录用户点一下就改数据"这类攻击。
 *
 * 说明：
 *  - 没有来源头的请求（curl、Postman、服务端调用）直接放行，避免影响脚本和健康检查；
 *  - session cookie 的 SameSite=Lax 是另一道保险，两者不冲突；
 *  - 将来若接入第三方回调，需要在这里放行对应来源。
 */
@Component
public class OriginCheckInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OriginCheckInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        // Origin 优先级更高：Referer 可能被 Referrer-Policy 裁掉路径，但 Origin 一定有协议+主机+端口
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank() || "null".equals(source)) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank()) {
            return true;
        }

        if (isSameOrigin(source, request)) {
            return true;
        }

        log.warn("拒绝跨站写操作：method={}, source={}, uri={}", method, source, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(
                "<h3>403 请求被拒绝</h3><p>来源校验未通过，请从站内页面重新操作。</p>");
        return false;
    }

    /** 只比较主机和端口，忽略协议，方便 http/https 混用的本地环境 */
    private boolean isSameOrigin(String source, HttpServletRequest request) {
        try {
            URI uri = URI.create(source);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String serverHost = request.getServerName();
            if (!host.equalsIgnoreCase(serverHost)) {
                // localhost / 127.0.0.1 视为同一来源，避免本地调试时被自己的页面挡住
                boolean loopback = ("localhost".equalsIgnoreCase(host) && "127.0.0.1".equals(serverHost))
                        || ("127.0.0.1".equals(host) && "localhost".equalsIgnoreCase(serverHost));
                if (!loopback) {
                    return false;
                }
            }
            int port = uri.getPort();
            return port == -1 || port == request.getServerPort();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
