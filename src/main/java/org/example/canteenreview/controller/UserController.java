package org.example.canteenreview.controller;

import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 用户控制器：只负责接请求、调 Service、控制页面跳转
 * HttpSession 属于 Web 层，所以留在 Controller，不搬进 Service
 */
@Controller
public class UserController {

    @Autowired
    private UserService userService; // 只依赖 Service，不再注入 Mapper

    // ================== 1. 注册 ==================

    @GetMapping("/register")
    public String showRegisterPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(String username, String password, Model model) {
        if (!userService.register(username, password)) {
            model.addAttribute("error", "用户名已被注册，换一个吧！");
            return "register";
        }
        return "redirect:/login";
    }

    // ================== 2. 登录 ==================

    @GetMapping("/login")
    public String showLoginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(String username, String password, Model model, HttpSession session) {
        User user = userService.login(username, password);
        if (user != null) {
            // 防止密码密文进入 Session：存进 Session 前先把密码清掉
            user.setPassword(null);
            session.setAttribute("loginUser", user); // 登录状态存 Session
            return "redirect:/dishes";
        }
        model.addAttribute("error", "账号或密码错误！");
        return "login";
    }

    // ================== 3. 退出登录 ==================

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/dishes";
    }
}
