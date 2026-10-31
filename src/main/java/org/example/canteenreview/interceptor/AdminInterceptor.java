package org.example.canteenreview.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员拦截器：只有 role == ADMIN 的登录用户才能访问后台。
 * 未登录跳登录页；已登录但不是管理员，返回 403。
 */
public class AdminInterceptor implements HandlerInterceptor {

    private static final String ADMIN_ROLE = "ADMIN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        User loginUser = session == null ? null : (User) session.getAttribute("loginUser");

        if (loginUser == null) {
            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }
        if (!ADMIN_ROLE.equals(loginUser.getRole())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return false;
        }
        return true;
    }
}
