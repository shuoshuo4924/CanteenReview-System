package org.example.canteenreview.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 统一注入安全响应头。
 *
 * <p>之前这些头一个都没有：后台页面能被 iframe 嵌去做点击劫持、
 * 浏览器会对上传文件"猜"类型（把 .jpg 当 html 解析）、外部页面能从 Referer 里读到站内路径。
 * 这些都是"加一次头就能挡住"的廉价防护，不值得等到出事再补。
 *
 * <p>CSP 里保留 {@code 'unsafe-inline'}：页面用的是内联 {@code <script>} 和内联事件处理器
 * （{@code onsubmit="return confirm(...)"}），去掉会让现有交互全部失效。
 * 即便如此，{@code default-src 'self'} 仍然限制了脚本、样式、接口请求只能打本站，
 * 挡得住"注入一个外链脚本把 session 偷走"这类最严重的玩法。
 *
 * <p>HSTS 只在 HTTPS 请求上下发。本地是 http，无条件下发会被浏览器记住，
 * 之后访问 http://localhost 会被强制跳 https，开发环境直接废掉。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * 允许 data: 和 blob: 的图片来源：
     * 下拉框箭头是 CSS 里的 data:svg，晒图预览用 URL.createObjectURL 生成 blob:。
     */
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "img-src 'self' data: blob:",
            "style-src 'self' 'unsafe-inline'",
            "script-src 'self' 'unsafe-inline'",
            "connect-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "object-src 'none'");

    /** 一年，含子域；只在 HTTPS 下发送 */
    private static final String HSTS = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 禁止浏览器按内容猜类型：上传目录里的文件必须按声明的类型处理
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 禁止被 iframe 嵌套（CSP 的 frame-ancestors 是同一件事的新写法，两个都发兼容老浏览器）
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CSP);
        // 跨站跳转时不带站内路径，配合 OriginCheckInterceptor 使用
        response.setHeader("Referrer-Policy", "same-origin");
        // 用不到的能力直接关掉，减少被恶意页面调用的面
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), payment=()");

        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        // 后台页面含用户和审核数据：不落磁盘缓存，共用电脑上点"后退"也不该能看到
        if (request.getRequestURI().startsWith("/admin")) {
            response.setHeader("Cache-Control", "no-store");
        }

        chain.doFilter(request, response);
    }
}
