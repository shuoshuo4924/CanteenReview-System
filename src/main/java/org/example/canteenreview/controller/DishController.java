package org.example.canteenreview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.common.RateLimiter;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.DishService;
import org.example.canteenreview.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 菜品展示控制器：首页列表 / 分页 / 详情 / 提交评价。
 * 只做"接请求 → 调 Service → 选视图"，业务规则都在 Service 层。
 */
@Controller
public class DishController {

    // 与 reviews.comment 列长度保持一致，超长会在入库时抛 SQL 异常
    private static final int MAX_COMMENT_LENGTH = 500;

    @Autowired
    private DishService dishService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RateLimiter rateLimiter;

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
                             HttpSession session, RedirectAttributes redirectAttributes) {
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

        return "dish-detail";
    }

    // 评价提交
    @PostMapping("/dish/review")
    public String addReview(Review review,
                            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                            HttpSession session,
                            RedirectAttributes redirectAttributes) {

        User loginUser = (User) session.getAttribute("loginUser");
        if (loginUser == null) {
            return "redirect:/login";
        }

        // 前端校验可以被绕过（直接 POST），这里必须再挡一遍，
        // 否则 rating 为 null / 越界会撞上数据库 NOT NULL 直接 500
        Long dishId = review.getDishId();
        if (dishId == null) {
            redirectAttributes.addFlashAttribute("errorMsg", "❌ 没找到要评价的菜品，请重新进入详情页");
            return "redirect:/dishes";
        }
        // 防刷：同一账号 1 分钟最多提交 10 次，挡掉连点器和脚本（换菜刷分还有唯一键兜底）
        if (!rateLimiter.tryAcquire("review:user:" + loginUser.getId(), 10, Duration.ofMinutes(1))) {
            redirectAttributes.addFlashAttribute("errorMsg", "❌ 评价提交太频繁了，歇一会儿再试");
            return "redirect:/dish/detail?id=" + dishId;
        }
        if (review.getRating() == null || review.getRating() < 1 || review.getRating() > 5) {
            redirectAttributes.addFlashAttribute("errorMsg", "❌ 评分要在 1~5 星之间哦");
            return "redirect:/dish/detail?id=" + dishId;
        }
        if (review.getComment() != null && review.getComment().trim().length() > MAX_COMMENT_LENGTH) {
            redirectAttributes.addFlashAttribute("errorMsg", "❌ 评价内容最多 " + MAX_COMMENT_LENGTH + " 字，精简一下再发吧");
            return "redirect:/dish/detail?id=" + dishId;
        }

        review.setUserId(loginUser.getId());
        review.setAuthor(loginUser.getUsername());

        boolean success = reviewService.submitReview(review, imageFile);

        if (!success) {
            redirectAttributes.addFlashAttribute("errorMsg", "❌ 提交失败：菜品可能已下架，或者你已经评价过这道菜啦！");
        }

        return "redirect:/dish/detail?id=" + dishId;
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
