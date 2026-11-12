package org.example.canteenreview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.common.RateLimiter;
import org.example.canteenreview.common.ReviewViolationGuard;
import org.example.canteenreview.common.SensitiveWordFilter;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.DishService;
import org.example.canteenreview.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜品展示控制器：首页列表 / 分页 / 详情 / 提交评价。
 * 只做"接请求 → 调 Service → 选视图"，业务规则都在 Service 层。
 */
@Controller
public class DishController {

    private static final Logger log = LoggerFactory.getLogger(DishController.class);

    // 与 reviews.comment 列长度保持一致，超长会在入库时抛 SQL 异常
    private static final int MAX_COMMENT_LENGTH = 500;

    /** 页面 fetch 提交时带的请求头：带它的走 JSON 分支，不带的按普通表单走跳转 */
    private static final String AJAX_HEADER = "X-Requested-With=XMLHttpRequest";

    /** 回给前端的评价时间格式，与模板里 #temporals.format 保持一致 */
    private static final DateTimeFormatter REVIEW_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private DishService dishService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private SensitiveWordFilter sensitiveWordFilter;

    // 违规阶梯处罚：多次发不合规内容后按次翻倍禁言
    @Autowired
    private ReviewViolationGuard reviewViolationGuard;

    @GetMapping("/")
    public String index() {
        return "redirect:/dishes";
    }

    // 首页：融合了 搜索 + 分
    @GetMapping("/dishes")
    public String showHomePage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "minRating", required = false) Double minRating,
            @RequestParam(value = "page", defaultValue = "1") Integer page,  // 页码
            @RequestParam(value = "size", defaultValue = "8") Integer size,  // 每页条数
            Model model,
            RedirectAttributes redirectAttributes) {

        boolean searching = (keyword != null && !keyword.isBlank()) || minRating != null;

        // 参数保护：页码至少为 1，每页条数限制在 1~50，防止非法参数拉爆数据库
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 8 : Math.min(size, 50);

        // 使用 MyBatis-Plus 的 Page 对象进行分页查询
        Page<Dish> pageParam = new Page<>(safePage, safeSize);
        Page<Dish> dishPage = dishService.searchDishesWithPage(keyword, minRating, pageParam);

        // 页码越界（只有 4 页却访问第 5 页）：重定向到最后一页，
        // 这样内容和地址栏同时被纠正，刷新也不会再错一次
        long totalPages = dishPage.getPages();
        if (totalPages > 0 && safePage > totalPages) {
            redirectAttributes.addAttribute("page", totalPages);
            redirectAttributes.addAttribute("size", safeSize);
            if (keyword != null && !keyword.isBlank()) {
                redirectAttributes.addAttribute("keyword", keyword);
            }
            if (minRating != null) {
                redirectAttributes.addAttribute("minRating", minRating);
            }
            return "redirect:/dishes";
        }

        // 传给前端的依然是列表，但加上了分页信息
        model.addAttribute("dishes", dishPage.getRecords());
        model.addAttribute("dishPage", dishPage); // 分页栏需要这个对象
        // 页码序列在服务端算好：页数少时就是 1..N，页数多时用 null 占位省略号
        model.addAttribute("pageNumbers", buildPageNumbers(dishPage.getCurrent(), dishPage.getPages()));

        model.addAttribute("topDishes", dishService.getTop10Dishes());

        // 回填搜索条件
        model.addAttribute("keyword", keyword);
        model.addAttribute("minRating", minRating);
        model.addAttribute("searching", searching);

        return "dish-list";
    }

    // 菜品详情
    @GetMapping("/dish/detail")
    public String showDetail(@RequestParam("id") Long id, Model model,
                             HttpSession session, RedirectAttributes redirectAttributes,
                             HttpServletRequest request) {
        Dish dish = dishService.getDishById(id);
        // 菜品可能被管理员删掉，地址栏里的 id 还停在旧链接上：
        // 此处必须判空，否则模板取 dish.name 会直接抛 500
        if (dish == null) {
            redirectAttributes.addFlashAttribute("errorMsg", "这道菜已经不在菜单里了，去逛逛别的吧~");
            return "redirect:/dishes";
        }

        model.addAttribute("dish", dish);
        model.addAttribute("reviews", reviewService.getReviewsByDishId(id));

        User loginUser = (User) session.getAttribute("loginUser");
        boolean hasReviewed = loginUser != null && reviewService.hasReviewed(id, loginUser.getId());
        model.addAttribute("hasReviewed", hasReviewed);

        // 正在被禁言时页面就要告诉用户，而不是等他填完一整条再被拒
        if (loginUser != null) {
            ReviewViolationGuard.Penalty penalty =
                    reviewViolationGuard.check(loginUser.getId(), rateLimiter.clientIp(request));
            if (penalty.blocked()) {
                model.addAttribute("reviewBlockedMsg", "❌ 因多次提交不合规内容，请 "
                        + ReviewViolationGuard.describeWait(penalty.remainingSeconds()) + "后再来评价");
            }
        }

        return "dish-detail";
    }

    /**
     * 评价提交（页面表单）。
     * 页面里其实走的是下面那个 AJAX 版本，这里保留普通表单出口：
     * 无 JS、脚本直连、以后新增的调用方都还能按老方式提交。
     */
    @PostMapping("/dish/review")
    public String addReview(Review review,
                            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                            HttpSession session,
                            RedirectAttributes redirectAttributes,
                            HttpServletRequest request) {

        User loginUser = (User) session.getAttribute("loginUser");
        if (loginUser == null) {
            return "redirect:/login";
        }

        SubmitOutcome outcome = doSubmit(review, imageFile, loginUser, rateLimiter.clientIp(request));
        redirectAttributes.addFlashAttribute(outcome.success() ? "okMsg" : "errorMsg", outcome.message());
        return "redirect:" + outcome.back();
    }

    /**
     * 评价提交（页面内 AJAX）。
     * 和上面的表单版共用一套校验，只是把结论换成 JSON，让前端就地插入评论、不刷新页面，
     * 用户也就不会因为刷新被弹回顶部、还得重新往下翻。
     */
    @PostMapping(value = "/dish/review", headers = AJAX_HEADER)
    @ResponseBody
    public Map<String, Object> addReviewAjax(Review review,
                                             @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                                             HttpSession session,
                                             HttpServletRequest request) {

        Map<String, Object> body = new HashMap<>();
        User loginUser = (User) session.getAttribute("loginUser");
        if (loginUser == null) {
            body.put("success", false);
            body.put("message", "❌ 登录状态已失效，请重新登录");
            body.put("redirect", "/login");
            return body;
        }

        SubmitOutcome outcome = doSubmit(review, imageFile, loginUser, rateLimiter.clientIp(request));
        body.put("success", outcome.success());
        body.put("message", outcome.message());
        if (outcome.success()) {
            body.put("pending", outcome.pending());
            body.put("review", outcome.review());
            if (outcome.avgRating() != null) {
                body.put("avgRating", outcome.avgRating());
            }
        }
        return body;
    }

    /**
     * 两种提交方式共用的校验与落库。
     * 顺序：登录 → 参数 → 违规封禁 → 防刷 → 评分 → 长度 → 敏感词 → 入库。
     */
    private SubmitOutcome doSubmit(Review review, MultipartFile imageFile, User loginUser, String clientIp) {
        // 前端校验可以被绕过（直接 POST），这里必须再挡一遍，
        // 否则 rating 为 null / 越界会撞上数据库 NOT NULL 直接 500
        Long dishId = review.getDishId();
        if (dishId == null) {
            return SubmitOutcome.fail("❌ 没找到要评价的菜品，请重新进入详情页", "/dishes");
        }
        String back = "/dish/detail?id=" + dishId;
        // 违规封禁优先于一切：在处罚期内的提交直接拒掉，连内容都不必再看
        ReviewViolationGuard.Penalty penalty = reviewViolationGuard.check(loginUser.getId(), clientIp);
        if (penalty.blocked()) {
            return SubmitOutcome.fail("❌ 因多次提交不合规内容，请 "
                    + ReviewViolationGuard.describeWait(penalty.remainingSeconds()) + "后再试", back);
        }
        // 防刷：同一账号 1 分钟最多提交 10 次，挡掉连点器和脚本（换菜刷分还有唯一键兜底）
        if (!rateLimiter.tryAcquire("review:user:" + loginUser.getId(), 10, Duration.ofMinutes(1))) {
            return SubmitOutcome.fail("❌ 评价提交太频繁了，歇一会儿再试", back);
        }
        if (review.getRating() == null || review.getRating() < 1 || review.getRating() > 5) {
            return SubmitOutcome.fail("❌ 评分要在 1~5 星之间哦", back);
        }
        if (review.getComment() != null && review.getComment().trim().length() > MAX_COMMENT_LENGTH) {
            return SubmitOutcome.fail("❌ 评价内容最多 " + MAX_COMMENT_LENGTH + " 字，精简一下再发吧", back);
        }
        // 评论过滤：不提示具体命中了哪个词，免得被拿去试探词库
        SensitiveWordFilter.GuardResult guard = sensitiveWordFilter.check(review.getComment());
        if (guard.hit()) {
            log.warn("评论提交被拦截：category={}, dishId={}, userId={}",
                    guard.category(), dishId, loginUser.getId());
            // 违规累计：用户维度前几次只记数，IP 维度做换号兜底，任一触发就告诉用户要等多久
            ReviewViolationGuard.Penalty after =
                    reviewViolationGuard.record(loginUser.getId(), clientIp, guard.category());
            if (after.blocked()) {
                return SubmitOutcome.fail("❌ 评价里有不太合适的内容，改一改再发吧（因多次不合规，"
                        + ReviewViolationGuard.describeWait(after.remainingSeconds()) + "内不能再提交）", back);
            }
            return SubmitOutcome.fail("❌ 评价里有不太合适的内容，改一改再发吧", back);
        }

        // 表单绑定会把同名参数一并塞进实体：主键和晒图地址必须由服务端决定。
        // 不清的话，直接 POST 就能指定评价 id、或塞入任意外链图片地址（存储型问题）。
        review.setId(null);
        review.setImageUrl(null);
        review.setCreateTime(null);

        review.setUserId(loginUser.getId());
        review.setAuthor(loginUser.getUsername());

        if (!reviewService.submitReview(review, imageFile)) {
            return SubmitOutcome.fail("❌ 提交失败：菜品可能已下架，或者你已经评价过这道菜啦！", back);
        }

        // 先审后发：告诉用户"提交成功但还没显示"，否则会以为是页面出问题
        boolean pending = reviewService.isAuditEnabled();
        String message = pending ? "✅ 评价已提交，审核通过后就会显示出来" : "✅ 评价已提交，感谢你的反馈";

        Double avgRating = null;
        if (!pending) {
            // 分数是库里的值，重新查一次比前端自己算更可靠（并发提交时也能对上）
            Dish dish = dishService.getDishById(review.getDishId());
            if (dish != null) {
                avgRating = dish.getAvgRating();
            }
        }
        return SubmitOutcome.ok(message, pending, back, reviewItem(review), avgRating);
    }

    /** 新评价的展示字段，字段名与前端拼 DOM 的地方一一对应 */
    private static Map<String, Object> reviewItem(Review review) {
        Map<String, Object> item = new HashMap<>();
        item.put("author", review.getAuthor() == null ? "" : review.getAuthor());
        item.put("rating", review.getRating());
        item.put("comment", review.getComment() == null ? "" : review.getComment());
        item.put("imageUrl", review.getImageUrl() == null ? "" : review.getImageUrl());
        item.put("createTime", review.getCreateTime() == null
                ? "" : review.getCreateTime().format(REVIEW_TIME_FORMAT));
        return item;
    }



    /** 一次提交的结论：两种出口（重定向 / JSON）都从这里取数据，避免校验逻辑写两份 */
    private record SubmitOutcome(boolean success, boolean pending, String message, String back,
                                 Map<String, Object> review, Double avgRating) {

        static SubmitOutcome fail(String message, String back) {
            return new SubmitOutcome(false, false, message, back, null, null);
        }

        static SubmitOutcome ok(String message, boolean pending, String back,
                                Map<String, Object> review, Double avgRating) {
            return new SubmitOutcome(true, pending, message, back, review, avgRating);
        }
    }

    /**
     * 生成页码栏要显示的页码：null 代表省略号。
     * 页数 ≤ 7 时直接返回全部页码（不需要省略号）；
     * 页数更多时只保留首尾 + 当前页前后各 2 页，中间用 null 收拢。
     * 这属于"怎么展示"的逻辑，留在 Controller。
     */
    private static List<Long> buildPageNumbers(long current, long total) {
        List<Long> numbers = new ArrayList<>();
        if (total <= 7) {
            for (long i = 1; i <= total; i++) {
                numbers.add(i);
            }
            return numbers;
        }
        numbers.add(1L);
        long start = Math.max(2, current - 2);
        long end = Math.min(total - 1, current + 2);
        if (start > 2) {
            numbers.add(null);
        }
        for (long i = start; i <= end; i++) {
            numbers.add(i);
        }
        if (end < total - 1) {
            numbers.add(null);
        }
        numbers.add(total);
        return numbers;
    }
}
