package org.example.canteenreview.controller;

import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.common.AiQuota;
import org.example.canteenreview.common.RateLimiter;
import org.example.canteenreview.common.ReviewViolationGuard;
import org.example.canteenreview.common.SensitiveWordFilter;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.AiService;
import org.example.canteenreview.service.DishService;
import org.example.canteenreview.service.ReviewService;
import org.example.canteenreview.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员后台控制器：菜品增删改。
 * 访问 /admin/** 的请求已被 AdminInterceptor 拦截，这里只处理业务。
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    private DishService dishService;

    @Autowired
    private AiService aiService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private SensitiveWordFilter sensitiveWordFilter;

    // 评论违规封禁：误判时需要在这里手动放人
    @Autowired
    private ReviewViolationGuard reviewViolationGuard;

    @Autowired
    private UserService userService;

    // ================== 菜品列表（支持按销售档位筛选 + 排序） ==================

    @GetMapping("/dishes")
    public String listDishes(@RequestParam(value = "level", required = false) String level,
                             @RequestParam(value = "sortBy", required = false) String sortBy,
                             Model model) {
        model.addAttribute("dishes", dishService.listDishesForAdmin(level, sortBy));
        // 回填筛选条件，让工具栏保持选中状态
        model.addAttribute("level", level);
        model.addAttribute("sortBy", sortBy);
        return "admin-dish";
    }

    // ================== 新增菜品 ==================

    @PostMapping("/dish/add")
    public String addDish(Dish dish,
                          @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                          HttpSession session,
                          RedirectAttributes redirectAttributes) {
        boolean success = dishService.addDish(dish, imageFile);
        // 审计留痕：后台改了什么、谁改的，出问题能倒查
        log.info("管理员[{}] 新增菜品 name={} canteen={} 结果={}",
                operator(session), dish.getName(), dish.getCanteen(), success ? "成功" : "失败");
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 菜品添加成功" : "❌ 添加失败：菜名不能为空，或该食堂下已存在同名菜品");
        return "redirect:/admin/dishes";
    }

    // ================== 修改菜品 ==================

    @PostMapping("/dish/update")
    public String updateDish(Dish dish,
                             @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        boolean success = dishService.updateDish(dish, imageFile);
        log.info("管理员[{}] 修改菜品 id={} name={} 结果={}",
                operator(session), dish.getId(), dish.getName(), success ? "成功" : "失败");
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 菜品更新成功" : "❌ 更新失败：该食堂下已存在同名菜品，或菜名不能为空");
        return "redirect:/admin/dishes";
    }

    // ================== 删除菜品 ==================

    @PostMapping("/dish/delete")
    public String deleteDish(@RequestParam("id") Long id,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        boolean success = dishService.deleteDish(id);
        log.info("管理员[{}] 删除菜品 id={} 结果={}", operator(session), id, success ? "成功" : "失败");
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 菜品已删除" : "❌ 删除失败");
        return "redirect:/admin/dishes";
    }

    // ================== AI 健康建议 ==================

    /**
     * 单道菜：生成一条健康建议并直接返回，不写库。
     * 前端填进编辑框，管理员看过、改过再点保存 —— 避免 AI 直接改生产数据。
     */
    @PostMapping("/dish/health-tip")
    @ResponseBody
    public ResponseEntity<Map<String, String>> generateHealthTip(@RequestParam("id") Long id) {
        Dish dish = dishService.getDishById(id);
        if (dish == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "菜品不存在"));
        }
        String tip = aiService.generateHealthTip(dish, reviewService.getReviewsByDishId(id));
        if (tip == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI 暂时不可用，稍后再试"));
        }
        return ResponseEntity.ok(Map.of("tip", tip));
    }

    /**
     * 批量：每次只处理 size 道「还没有建议」的菜，由前端循环调用直到 remaining = 0。
     *
     * 为什么必须分批：每道菜都要等模型返回（几秒），32 道堆在一个请求里必然超时，
     * 而且中途失败会全部回滚，等于白花钱。分批后每批独立成功，进度也可见。
     */
    @PostMapping("/dish/health-tip/batch")
    @ResponseBody
    public Map<String, Object> batchGenerateHealthTips(
            @RequestParam(value = "size", defaultValue = "3") Integer size,
            HttpSession session) {

        long remaining = dishService.countDishesWithoutHealthTip();
        if (remaining == 0) {
            return Map.of("done", 0, "failed", 0, "remaining", 0);
        }
        if (!rateLimiter.tryAcquire(AiQuota.dailyKey(), AiQuota.DAILY_LIMIT, Duration.ofDays(1))) {
            return Map.of("done", 0, "failed", 0, "remaining", remaining,
                    "error", "今天的 AI 额度用完了，明天再试");
        }

        int batchSize = (size == null || size < 1) ? 3 : size;
        List<Dish> targets = dishService.listDishesWithoutHealthTip(batchSize);
        int done = 0;
        int failed = 0;
        for (Dish dish : targets) {
            String tip = aiService.generateHealthTip(dish, reviewService.getReviewsByDishId(dish.getId()));
            if (tip != null && dishService.updateHealthTip(dish.getId(), tip)) {
                done++;
                log.info("管理员[{}] AI 生成健康建议 dishId={} name={} tip={}",
                        operator(session), dish.getId(), dish.getName(), tip);
            } else {
                failed++;
            }
        }
        return Map.of("done", done, "failed", failed,
                "remaining", dishService.countDishesWithoutHealthTip());
    }

    // ================== 评论审核（先审后发） ==================

    @GetMapping("/reviews")
    public String listReviews(@RequestParam(value = "status", required = false) String status,
                              Model model) {
        Integer statusValue = toStatusValue(status);
        model.addAttribute("reviews", reviewService.listReviewsForAdmin(statusValue));

        // 评价表里只有 dishId，审核的人需要看到菜名，这里一次性翻译，避免逐条查库
        Map<Long, String> dishNames = new HashMap<>();
        for (Dish dish : dishService.listDishesForAdmin(null, null)) {
            dishNames.put(dish.getId(), dish.getName());
        }
        model.addAttribute("dishNames", dishNames);

        model.addAttribute("status", status);
        model.addAttribute("pendingCount", reviewService.countPending());
        model.addAttribute("auditEnabled", reviewService.isAuditEnabled());
        // 最近 50 条审核记录：拒绝会把评价物理删除，这里是唯一的追溯入口
        model.addAttribute("auditLogs", reviewService.listAuditLogs(50));

        // 词库状态：改完 txt 不用重启，这里能看到当前生效的条数和来源
        model.addAttribute("sensitiveWordCount", sensitiveWordFilter.size());
        model.addAttribute("sensitiveWordEntries", sensitiveWordFilter.entrySize());
        model.addAttribute("sensitiveWordSource", sensitiveWordFilter.describeDictSource());

        // 阶梯处罚状态：机器判错时这里能立刻解除，不用等时间到或者重启
        model.addAttribute("penalties", buildPenaltyRows());
        return "admin-review";
    }

    /** 把封禁记录翻译成页面直接能渲染的行；用户维度顺便查一下用户名 */
    private List<Map<String, Object>> buildPenaltyRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReviewViolationGuard.Blocked blocked : reviewViolationGuard.blocked()) {
            Map<String, Object> row = new HashMap<>();
            row.put("key", blocked.key());
            row.put("count", blocked.violationCount());
            row.put("remaining", ReviewViolationGuard.describeWait(blocked.remainingSeconds()));
            // 命中类别：运营据此判断是"词库缺词"还是"规则误伤"
            row.put("category", blocked.lastCategory() == null || blocked.lastCategory().isBlank()
                    ? "未记录" : blocked.lastCategory());
            if (blocked.userId() != null) {
                User user = userService.findById(blocked.userId());
                row.put("scope", "用户");
                row.put("subject", user == null
                        ? "已删除账号 #" + blocked.userId() : user.getUsername());
            } else {
                row.put("scope", "IP");
                row.put("subject", blocked.ip());
            }
            rows.add(row);
        }
        return rows;
    }

    @PostMapping("/reviews/approve")
    public String approveReview(@RequestParam("id") Long id,
                                @RequestParam(value = "status", required = false) String status,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {
        boolean success = reviewService.approveReview(id, operator(session));
        log.info("管理员[{}] 审核通过评价 id={} 结果={}", operator(session), id, success ? "成功" : "失败");
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 评价已通过，前台立即可见" : "❌ 通过失败：评价可能已被删除");
        return "redirect:/admin/reviews" + statusQuery(status);
    }

    @PostMapping("/reviews/reject")
    public String rejectReview(@RequestParam("id") Long id,
                               @RequestParam(value = "status", required = false) String status,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        boolean success = reviewService.rejectReview(id, operator(session));
        log.info("管理员[{}] 审核拒绝评价 id={} 结果={}", operator(session), id, success ? "成功" : "失败");
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 评价已拒绝并删除，用户可以重新评价" : "❌ 删除失败：评价可能已被删除");
        return "redirect:/admin/reviews" + statusQuery(status);
    }

    // ================== 违规禁言 ==================

    /**
     * 手动解除一条违规封禁。
     * 机器判错（词库误伤、正则误命中）时唯一的人工出口，不用等时间到，也不用重启。
     */
    @PostMapping("/reviews/penalty/clear")
    public String clearPenalty(@RequestParam("key") String key,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        boolean success = reviewViolationGuard.clear(key);
        if (success) {
            log.info("管理员[{}] 解除评论禁言 key={}", operator(session), key);
        }
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 已解除：" + key + "，对方可以马上继续评论"
                        : "❌ 这条封禁记录已经不在了（可能已到期自动解除）");
        return "redirect:/admin/reviews";
    }

    // ================== 敏感词库热重载 ==================

    @PostMapping("/sensitive-words/reload")
    public String reloadSensitiveWords(HttpSession session, RedirectAttributes redirectAttributes) {
        boolean success = sensitiveWordFilter.reload();
        log.info("管理员[{}] 重载敏感词库 结果={}", operator(session), success ? "成功" : "失败");
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success
                        ? "✅ 词库已重载，当前 " + sensitiveWordFilter.size() + " 条词"
                        : "❌ 重载失败，请检查词库文件是否存在、格式是否正确");
        return "redirect:/admin/reviews";
    }

    /** 页面上的筛选值是字符串，转成实体里的 0/1；null 表示查全部 */
    private static Integer toStatusValue(String status) {
        if ("PENDING".equals(status)) {
            return ReviewService.STATUS_PENDING;
        }
        if ("APPROVED".equals(status)) {
            return ReviewService.STATUS_APPROVED;
        }
        return null;
    }

    /** 操作完回到原来的筛选视图，不要让管理员每次都跳回"全部" */
    private static String statusQuery(String status) {
        return (status == null || status.isBlank()) ? "" : "?status=" + status;
    }

    /** 审计日志里的操作人；Session 由 AdminInterceptor 保证一定存在，这里只做兜底 */
    private String operator(HttpSession session) {
        User loginUser = session == null ? null : (User) session.getAttribute("loginUser");
        return loginUser == null ? "unknown" : loginUser.getUsername();
    }
}
