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
import java.util.Locale;

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

    /** 同一账号连续答错几次密保就临时锁住 */
    private static final int MAX_ANSWER_FAILS = 5;

    /** 锁定时长：到期自动解锁，不需要人工干预 */
    private static final Duration ANSWER_LOCK = Duration.ofMinutes(10);

    /** 找回密码两个步骤的 IP 限流参数（挡批量枚举用户名 / 批量试答案） */
    private static final int FORGOT_IP_LIMIT = 10;
    private static final Duration FORGOT_IP_WINDOW = Duration.ofMinutes(10);

    /** 同一账号连续密码错误几次就临时锁住（IP 维度挡不住换 IP 的撞库，账号维度才挡得住） */
    private static final int MAX_LOGIN_FAILS = 5;
    private static final Duration LOGIN_LOCK = Duration.ofMinutes(10);

    // ================== 1. 注册 ==================

    @GetMapping("/register")
    public String showRegisterPage(Model model) {
        // 密保问题由用户自选，页面渲染成下拉框
        model.addAttribute("securityQuestions", UserService.SECURITY_QUESTIONS);
        return "register";
    }

    @PostMapping("/register")
    public String register(String username, String password, String securityQuestion, String securityAnswer,
                           Model model, HttpServletRequest request) {
        // 出错回显页面时要把下拉框重新渲染出来
        model.addAttribute("securityQuestions", UserService.SECURITY_QUESTIONS);

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
        if (!userService.register(name, password, securityQuestion, securityAnswer)) {
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

        // 直接 POST 可以不带字段：password 为 null 时 BCrypt 的 matches 会抛异常 → 500
        if (isBlank(username) || isBlank(password)) {
            model.addAttribute("error", "用户名和密码都要填哦");
            return "login";
        }

        // 账号维度锁定：攻击者换 IP 就绕过了上面那层，这里按"被尝试的账号"计数。
        // 不查库、不管账号是否存在都计数，所以"被锁"这件事本身不会暴露用户名是否真实存在。
        String failKey = loginFailKey(username);
        if (!rateLimiter.isAllowed(failKey, MAX_LOGIN_FAILS, LOGIN_LOCK)) {
            long wait = Math.max(rateLimiter.retryAfterSeconds(failKey, LOGIN_LOCK), 1);
            model.addAttribute("error", "密码错误次数过多，请 " + wait + " 秒后再试");
            return "login";
        }

        User user = userService.login(username, password);
        if (user != null) {
            // 登录成功清空失败计数，避免前几次手误把用户连带锁住
            rateLimiter.reset(failKey);
            // 防止密文进入 Session：存进 Session 前先把密码、密保答案清掉
            user.setPassword(null);
            user.setSecurityAnswer(null);
            // 登录成功后换一个新的 sessionId，避免会话固定攻击
            request.changeSessionId();
            session.setAttribute("loginUser", user); // 登录状态存 Session
            return "redirect:/dishes";
        }

        // 只有"密码错/用户不存在"才记账；用户名密码为空的表单校验不算
        rateLimiter.record(failKey);
        // 不区分「用户不存在」和「密码错误」：否则就成了免费的用户名探测器
        model.addAttribute("error", "用户名或密码不正确");
        return "login";
    }

    /** 账号维度的限流 key：统一小写去空格，避免换个大小写就绕过锁定 */
    private static String loginFailKey(String username) {
        return "login:user:" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
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
    public String findQuestion(String username, Model model, HttpServletRequest request) {
        // 挡用户名枚举：同一 IP 10 分钟最多探测 10 个用户名
        if (!rateLimiter.tryAcquire("forgot:ip:" + rateLimiter.clientIp(request),
                FORGOT_IP_LIMIT, FORGOT_IP_WINDOW)) {
            model.addAttribute("error", "尝试次数太多了，请 10 分钟后再试");
            return "forgot";
        }

        if (isBlank(username)) {
            model.addAttribute("error", "请先填写用户名");
            return "forgot";
        }

        User user = userService.findByUsername(username);

        // 统一话术：不区分"用户名不存在"和"没设置密保"。
        // 分开提示等于免费提供一个用户名探测器，配合社工就能筛出真实账号。
        if (user == null || isBlank(user.getSecurityQuestion())) {
            model.addAttribute("error", "无法通过密保自助重置：账号不存在或未设置密保，请联系管理员处理");
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
                                String newPassword, String confirmPassword,
                                Model model, HttpServletRequest request) {
        // 防爆破第一层：同一 IP 10 分钟最多 10 次尝试
        if (!rateLimiter.tryAcquire("forgot:reset:ip:" + rateLimiter.clientIp(request),
                FORGOT_IP_LIMIT, FORGOT_IP_WINDOW)) {
            model.addAttribute("error", "尝试次数太多了，请 10 分钟后再试");
            return "forgot";
        }

        User user = userService.findByUsername(username);

        // 无论校验是否通过，第二步页面都需要问题文本，先回填
        if (user == null || isBlank(user.getSecurityQuestion())) {
            model.addAttribute("error", "账号信息已失效，请重新开始找回密码");
            return "forgot";
        }
        model.addAttribute("username", user.getUsername());
        model.addAttribute("question", user.getSecurityQuestion());

        // 防爆破第二层：同一账号连续答错 5 次锁 10 分钟。
        // 全站密保问题是固定的，答案空间有限，不锁住就能像字典一样一路试下去。
        String failKey = "forgot:reset:user:" + user.getUsername();
        if (!rateLimiter.isAllowed(failKey, MAX_ANSWER_FAILS, ANSWER_LOCK)) {
            long wait = Math.max(rateLimiter.retryAfterSeconds(failKey, ANSWER_LOCK), 1);
            model.addAttribute("error", "密保答案错误次数过多，请 " + wait + " 秒后再试");
            return "forgot";
        }

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
            // 只把"答错"记进失败计数；密码长度/两次不一致属于手误，不占用额度
            rateLimiter.record(failKey);
            model.addAttribute("error", "密保答案不正确，再想想？（连错 " + MAX_ANSWER_FAILS + " 次会锁定 10 分钟）");
            return "forgot";
        }

        // 重置成功：清空该账号的失败记录，避免前面几次手误把用户连带锁住
        rateLimiter.reset(failKey);
        return "redirect:/login?reset=1";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
