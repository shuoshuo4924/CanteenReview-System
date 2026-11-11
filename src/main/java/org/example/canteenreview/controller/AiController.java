package org.example.canteenreview.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.common.RateLimiter;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.Suggestion;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.AiService;
import org.example.canteenreview.service.DishService;
import org.example.canteenreview.service.ReviewService;
import org.example.canteenreview.service.SuggestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AI 接口：只负责取素材、交给 AiService、返回结果。
 * 对话历史裁剪、Prompt 组装、token 限制都在 AiServiceImpl 里。
 *
 * 三个入口的权限不一样：
 *   /ai/summary     所有人可用（详情页按钮），按 IP 限流
 *   /ai/chat        所有人可用（首页对话框），按 IP 限流
 *   /ai/suggestions 仅管理员可用（建议箱里的 AI 助手）
 */
@Controller
public class AiController {

    private static final String ADMIN_ROLE = "ADMIN";

    /** 单 IP 每分钟最多点几次「AI 总结」 */
    private static final int SUMMARY_LIMIT_PER_MINUTE = 5;

    /** 单 IP / 单账号每分钟最多问几次 */
    private static final int CHAT_LIMIT_PER_MINUTE = 20;

    /**
     * 全站每天最多调用多少次 AI。
     * 按 IP 限流管不住"换 IP 刷"和"多人共用一个出口"，
     * 这里再加一道总闸，防止某天账单突然失控。
     */
    private static final int DAILY_TOTAL_LIMIT = 2000;
    private static final String DAILY_TOTAL_KEY_PREFIX = "ai:global:daily:";
    private static final String DAILY_TOTAL_EXCEEDED = "今天的 AI 额度已经用完了，明天再来吧~";

    /**
     * 用日期做 key，额度才是「自然日」重置。
     * 若只用一个固定 key + 1 天窗口，那是滚动 24 小时：
     * 凌晨 1 点用完要等到第二天 1 点，和"明天再来"的提示对不上。
     */
    private static String dailyTotalKey() {
        return DAILY_TOTAL_KEY_PREFIX + LocalDate.now();
    }

    @Autowired
    private AiService aiService;

    @Autowired
    private DishService dishService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private SuggestionService suggestionService;

    @Autowired
    private RateLimiter rateLimiter;

    /**
     * 一句话总结某道菜的评价（详情页按钮调用），返回纯文本。
     */
    @GetMapping("/ai/summary")
    @ResponseBody
    public String summarize(@RequestParam("id") Long id, HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("ai:summary:ip:" + rateLimiter.clientIp(request),
                SUMMARY_LIMIT_PER_MINUTE, Duration.ofMinutes(1))) {
            return "点得有点快啦，一分钟后再试试~";
        }
        if (!rateLimiter.tryAcquire(dailyTotalKey(), DAILY_TOTAL_LIMIT, Duration.ofDays(1))) {
            return DAILY_TOTAL_EXCEEDED;
        }

        Dish dish = dishService.getDishById(id);
        if (dish == null) {
            return "菜品不存在";
        }
        List<Review> reviews = reviewService.getReviewsByDishId(id);
        return aiService.summarizeReviews(dish.getName(), reviews);
    }

    /**
     * 首页对话框：前端每次带完整对话历史，返回 {"reply": "..."}。
     * 访客也能用，所以只做 IP 限流，不做身份校验。
     */
    @PostMapping("/ai/chat")
    @ResponseBody
    public Map<String, String> chat(@RequestBody(required = false) List<Map<String, String>> messages,
                                    HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("ai:chat:ip:" + rateLimiter.clientIp(request),
                CHAT_LIMIT_PER_MINUTE, Duration.ofMinutes(1))) {
            return Map.of("reply", "问得太快啦，歇一分钟再聊~");
        }
        if (!rateLimiter.tryAcquire(dailyTotalKey(), DAILY_TOTAL_LIMIT, Duration.ofDays(1))) {
            return Map.of("reply", DAILY_TOTAL_EXCEEDED);
        }
        return Map.of("reply", aiService.chat(messages));
    }

    /**
     * 建议箱 AI 助手：只有管理员能用（页面入口也只给管理员渲染）。
     * 服务端必须自己校验，否则普通用户直接 POST 就能绕过页面限制。
     */
    @PostMapping("/ai/suggestions")
    @ResponseBody
    public ResponseEntity<Map<String, String>> suggestionsChat(
            @RequestBody(required = false) List<Map<String, String>> messages,
            HttpSession session) {

        User loginUser = (User) session.getAttribute("loginUser");
        if (loginUser == null || !ADMIN_ROLE.equals(loginUser.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("reply", "AI 助手仅管理员可用"));
        }
        if (!rateLimiter.tryAcquire("ai:suggestions:user:" + loginUser.getId(),
                CHAT_LIMIT_PER_MINUTE, Duration.ofMinutes(1))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("reply", "问得太快啦，歇一分钟再聊~"));
        }
        if (!rateLimiter.tryAcquire(dailyTotalKey(), DAILY_TOTAL_LIMIT, Duration.ofDays(1))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("reply", DAILY_TOTAL_EXCEEDED));
        }

        List<Suggestion> suggestions = suggestionService.listForAdmin(null);
        return ResponseEntity.ok(Map.of("reply", aiService.chatAboutSuggestions(suggestions, messages)));
    }
}
