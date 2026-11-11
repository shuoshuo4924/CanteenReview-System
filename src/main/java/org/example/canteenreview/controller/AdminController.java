package org.example.canteenreview.controller;

import jakarta.servlet.http.HttpSession;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.service.DishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    /** 审计日志里的操作人；Session 由 AdminInterceptor 保证一定存在，这里只做兜底 */
    private String operator(HttpSession session) {
        User loginUser = session == null ? null : (User) session.getAttribute("loginUser");
        return loginUser == null ? "unknown" : loginUser.getUsername();
    }
}
