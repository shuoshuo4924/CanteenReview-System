package org.example.canteenreview.controller;

import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.service.DishService;
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
                          RedirectAttributes redirectAttributes) {
        boolean success = dishService.addDish(dish, imageFile);
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 菜品添加成功" : "❌ 添加失败：菜名不能为空，或该食堂下已存在同名菜品");
        return "redirect:/admin/dishes";
    }

    // ================== 修改菜品 ==================

    @PostMapping("/dish/update")
    public String updateDish(Dish dish,
                             @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                             RedirectAttributes redirectAttributes) {
        boolean success = dishService.updateDish(dish, imageFile);
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 菜品更新成功" : "❌ 更新失败：该食堂下已存在同名菜品，或菜名不能为空");
        return "redirect:/admin/dishes";
    }

    // ================== 删除菜品 ==================

    @PostMapping("/dish/delete")
    public String deleteDish(@RequestParam("id") Long id,
                             RedirectAttributes redirectAttributes) {
        boolean success = dishService.deleteDish(id);
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 菜品已删除" : "❌ 删除失败");
        return "redirect:/admin/dishes";
    }
}
