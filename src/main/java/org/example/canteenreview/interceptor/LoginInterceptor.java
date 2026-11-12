package org.example.canteenreview.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 登录拦截器：拦截需要登录才能访问的路径。
 *
 * 除了看 Session 里有没有人，每次还会回查一次数据库：
 * Session 里存的是登录那一刻的 User 快照，账号被删除、角色被调整后，
 * 旧快照仍会继续生效（最长 30 分钟）。回查一次就能让变更立即生效。
 * 拦截范围只有 /dish/review 和 /admin/**，这点查询开销可以忽略。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        User loginUser = session == null ? null : (User) session.getAttribute("loginUser");
        if (loginUser == null) {
            reject(request, response);
            return false;
        }

        User fresh = userMapper.selectById(loginUser.getId());
        if (fresh == null) {
            // 账号已被删除：直接作废会话，别让幽灵用户继续操作
            session.invalidate();
            reject(request, response);
            return false;
        }

        // 用最新的账号信息刷新 Session，角色变化立刻生效；敏感字段一律不落 Session
        fresh.setPassword(null);
        fresh.setSecurityAnswer(null);
        session.setAttribute("loginUser", fresh);
        return true;
    }

    /**
     * 未登录处理。
     * 页面里的提交是 fetch，如果这里回 302，前端拿到的会是一整页登录 HTML 而解析失败，
     * 所以 AJAX 请求直接回 JSON，让页面提示"登录失效"并跳转。
     */
    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isAjax(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"❌ 登录状态已失效，请重新登录\",\"redirect\":\"/login\"}");
            return;
        }
        response.sendRedirect(request.getContextPath() + "/login");
    }

    private static boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }
}
