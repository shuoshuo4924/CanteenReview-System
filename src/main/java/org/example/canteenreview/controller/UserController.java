package org.example.canteenreview.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.common.RateLimiter;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;

/**
 * 用户控制器：只负责接请求、调 Service、控制页面跳转
 * HttpSession 属于 Web 层，所以留在 Controller，不搬进 Service
 */
@Controller
public class UserController {

    @Autowired
    private UserService userService; // 只依赖 Service，不再注入 Mapper

    @Autowired
    private RateLimiter rateLimiter;

    // ================== 1. 注册 ==================

    @GetMapping("/register")
    public String showRegisterPage(Model model) {
        // 密保问题是全站统一的，注册页只做展示
        model.addAttribute("securityQuestion", UserService.SECURITY_QUESTION);
        return "register";
    }

    @PostMapping("/register")
    public String register(String username, String password, String securityAnswer,
                           Model model, HttpServletRequest request) {
        // 出错回显页面时也要带上问题文本
        model.addAttribute("securityQuestion", UserService.SECURITY_QUESTION);

        // 防刷：同一 IP 10 分钟最多注册 5 次，挡掉批量注册小号
        if (!rateLimiter.tryAcquire("register:ip:" + rateLimiter.clientIp(request), 5, Duration.ofMinutes(10))) {
            model.addAttribute("error", "注册太频繁了，请 10 分钟后再试");
            return "register";
        }

        // 表单校验可以被绕过（直接 POST），用户名/密码为 null 时
        // BCrypt 会抛 IllegalArgumentException、入库会违反 NOT NULL，最终都是 500
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            model.addAttribute("error", "用户名不能为空哦！");
            return "register";
        }
        if (name.length() > 20) {
            model.addAttribute("error", "用户名最多 20 个字符");
            return "register";
        }
        if (isBlank(password)) {
            model.addAttribute("error", "密码不能为空哦！");
            return "register";
        }
        // 与找回密码页保持一致：至少 6 位；上限 64 位避免 BCrypt 超长输入的边界问题
        if (password.length() < 6 || password.length() > 64) {
            model.addAttribute("error", "密码长度要在 6~64 位之间");
            return "register";
        }
        // 密保是找回密码的唯一凭据，注册时必须填
        if (isBlank(securityAnswer)) {
            model.addAttribute("error", "密保答案不能为空哦！");
            return "register";
        }
        if (!userService.register(name, password, securityAnswer)) {
            model.addAttribute("error", "用户名已被注册，换一个吧！");
            return "register";
        }
        return "redirect:/login";
    }

    // ================== 2. 登录 ==================

    @GetMapping("/login")
    public String showLoginPage(@RequestParam(value = "reset", required = false) String reset, Model model) {
        if (reset != null) {
            model.addAttribute("ok", "密码已重置，请用新密码登录");
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(String username, String password, Model model,
                        HttpSession session, HttpServletRequest request) {
        // 防刷：同一 IP 10 分钟最多尝试 20 次，挡掉简单的密码爆破
        if (!rateLimiter.tryAcquire("login:ip:" + rateLimiter.clientIp(request), 20, Duration.ofMinutes(10))) {
            model.addAttribute("error", "登录尝试过于频繁，休息 10 分钟再试");
            return "login";
        }

        User user = userService.login(username, password);
        if (user != null) {
            // 防止密文进入 Session：存进 Session 前先把密码、密保答案清掉
            user.setPassword(null);
            user.setSecurityAnswer(null);
            // 登录成功后换一个新的 sessionId，避免会话固定攻击
            request.changeSessionId();
            session.setAttribute("loginUser", user); // 登录状态存 Session
            return "redirect:/dishes";
        }

        // 不区分「用户不存在」和「密码错误」：否则就成了免费的用户名探测器
        model.addAttribute("error", "用户名或密码不正确");
        return "login";
    }

    // ================== 3. 退出登录 ==================

    // 必须是 POST：用 GET 的话，一张 <img src="/logout"> 就能把用户踢下线
    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/dishes";
    }

    // ================== 4. 忘记密码（密保问题重置） ==================

    // 第一步：输入用户名
    @GetMapping("/forgot")
    public String showForgotPage() {
        return "forgot";
    }

    // 第一步提交：校验用户名，查出密保问题后进入第二步
    @PostMapping("/forgot")
    public String findQuestion(String username, Model model) {
        User user = userService.findByUsername(username);

        if (user == null) {
            model.addAttribute("error", "该用户名不存在，请检查后重试");
            return "forgot";
        }
        // 注意：findByUsername 已经把密保答案清空，这里只能靠"问题是否为空"判断
        if (isBlank(user.getSecurityQuestion())) {
            model.addAttribute("error", "该账号还没有设置密保问题，无法自助重置，请联系管理员");
            return "forgot";
        }

        // 页面进入第二步：username 用隐藏域回传，question 显示给用户
        model.addAttribute("username", user.getUsername());
        model.addAttribute("question", user.getSecurityQuestion());
        return "forgot";
    }

    // 第二步提交：校验密保答案并重置密码
    @PostMapping("/forgot/reset")
    public String resetPassword(String username, String answer,
                                String newPassword, String confirmPassword, Model model) {
        User user = userService.findByUsername(username);

        // 无论校验是否通过，第二步页面都需要问题文本，先回填
        if (user == null || isBlank(user.getSecurityQuestion())) {
            model.addAttribute("error", "账号信息已失效，请重新开始找回密码");
            return "forgot";
        }
        model.addAttribute("username", user.getUsername());
        model.addAttribute("question", user.getSecurityQuestion());

        if (isBlank(answer)) {
            model.addAttribute("error", "请填写密保答案");
            return "forgot";
        }
        if (newPassword == null || newPassword.length() < 6) {
            model.addAttribute("error", "新密码至少 6 位");
            return "forgot";
        }
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "两次输入的新密码不一致");
            return "forgot";
        }
        if (!userService.resetPassword(user.getUsername(), answer, newPassword)) {
            model.addAttribute("error", "密保答案不正确，再想想？");
            return "forgot";
        }
        return "redirect:/login?reset=1";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
