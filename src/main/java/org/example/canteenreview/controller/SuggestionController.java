package org.example.canteenreview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.common.RateLimiter;
import org.example.canteenreview.entity.Suggestion;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.SuggestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.List;

/**
 * 建议箱（前台）：所有人都能看「大家的声音」，登录后才能提交。
 * 「我的建议」单独展示自己的全部留言（含还没处理的），避免用户以为留言丢了。
 */
@Controller
@RequestMapping("/suggestions")
public class SuggestionController {

    /** 同一个账号每天最多发多少条建议 */
    private static final int MAX_DAILY_SUGGESTIONS = 20;

    /** 两条建议之间的冷却时间（秒） */
    private static final long SUGGESTION_COOLDOWN_SECONDS = 60;

    @Autowired
    private SuggestionService suggestionService;

    @Autowired
    private RateLimiter rateLimiter;

    @GetMapping
    public String showSuggestions(@RequestParam(value = "page", defaultValue = "1") Integer page,
                                  @RequestParam(value = "size", defaultValue = "6") Integer size,
                                  Model model,
                                  HttpSession session) {
        // 管理员来这里是为了处理留言，直接送去后台（前台页面对他没有意义）
        User loginUser = (User) session.getAttribute("loginUser");
        if (loginUser != null && "ADMIN".equals(loginUser.getRole())) {
            return "redirect:/admin/suggestions";
        }

        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 6 : Math.min(size, 50);

        Page<Suggestion> suggestionPage = suggestionService.pageReplied(safePage, safeSize);
        // 页码越界（比如第 3 页的留言被删了）：纠正到最后一页，和首页的做法保持一致
        long totalPages = suggestionPage.getPages();
        if (totalPages > 0 && safePage > totalPages) {
            return "redirect:/suggestions?page=" + totalPages + "&size=" + safeSize;
        }

        List<Suggestion> mySuggestions = loginUser == null
                ? List.of()
                : suggestionService.listByUserId(loginUser.getId());

        model.addAttribute("suggestionPage", suggestionPage);
        model.addAttribute("mySuggestions", mySuggestions);
        return "suggestions";
    }

    @PostMapping
    public String submit(Suggestion suggestion,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        User loginUser = (User) session.getAttribute("loginUser");
        if (loginUser == null) {
            return "redirect:/login";
        }

        // 防刷：按账号限流（换 IP 也拦得住），60 秒 1 条 + 每天 20 条
        String cooldownKey = "suggestion:cooldown:" + loginUser.getId();
        String dailyKey = "suggestion:daily:" + loginUser.getId();
        Duration cooldown = Duration.ofSeconds(SUGGESTION_COOLDOWN_SECONDS);

        if (!rateLimiter.isAllowed(cooldownKey, 1, cooldown)) {
            long wait = Math.max(1, rateLimiter.retryAfterSeconds(cooldownKey, cooldown));
            redirectAttributes.addFlashAttribute("errorMsg",
                    "❌ 提交太频繁了，" + wait + " 秒后再发吧");
            return "redirect:/suggestions#write";
        }
        if (!rateLimiter.isAllowed(dailyKey, MAX_DAILY_SUGGESTIONS, Duration.ofDays(1))) {
            redirectAttributes.addFlashAttribute("errorMsg",
                    "❌ 今天已经发了 " + MAX_DAILY_SUGGESTIONS + " 条建议，明天再来吧");
            return "redirect:/suggestions#write";
        }

        // 提交人不采信前端传值，一律用 Session 里的登录信息
        suggestion.setUserId(loginUser.getId());
        suggestion.setAuthor(loginUser.getUsername());

        if (!suggestionService.submit(suggestion)) {
            redirectAttributes.addFlashAttribute("errorMsg",
                    "❌ 提交失败：内容不能为空，且不超过 " + SuggestionService.MAX_CONTENT_LENGTH + " 字");
            return "redirect:/suggestions#write";
        }

        // 只有真正入库成功才占用额度，避免校验失败也把次数用掉
        rateLimiter.record(cooldownKey);
        rateLimiter.record(dailyKey);

        redirectAttributes.addFlashAttribute("okMsg", "✅ 建议已收到，管理员回复后会出现在「大家的声音」里");
        return "redirect:/suggestions";
    }
}
