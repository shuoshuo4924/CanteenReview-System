package org.example.canteenreview.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员拦截器：只有 role == ADMIN 的登录用户才能访问后台。
 * 未登录跳登录页；已登录但不是管理员，返回 403。
 *
 * 角色判定以数据库为准，而不是 Session 里的快照：
 * 管理员被降权后，手里的旧 Session 不能继续拥有后台权限。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private static final String ADMIN_ROLE = "ADMIN";

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        User loginUser = session == null ? null : (User) session.getAttribute("loginUser");

        if (loginUser == null) {
            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }

        // 回查数据库：账号可能已被删除，或角色已经改成 USER
        User fresh = userMapper.selectById(loginUser.getId());
        if (fresh == null) {
            session.invalidate();
            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }
        fresh.setPassword(null);
        fresh.setSecurityAnswer(null);
        session.setAttribute("loginUser", fresh);

        if (!ADMIN_ROLE.equals(fresh.getRole())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return false;
        }
        return true;
    }
}
