package org.example.canteenreview.service;

import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 菜品业务接口：Controller 只跟它打交道，不碰 Mapper
 * 注意：不继承 IService，只依赖 mybatis-plus-core，避开 extension 模块问题
 */
public interface DishService {

    // 查所有菜品
    List<Dish> listAllDishes();

    // 搜索菜品：keyword 匹配菜名/食堂（模糊），minRating 为最低评分（null 表示不限）
    List<Dish> searchDishes(String keyword, Double minRating);

    // 按 id 查单个菜品
    Dish getDishById(Long id);

    // 提交评价（含图片上传 + 自动重算平均分）
    boolean submitReview(Review review, MultipartFile imageFile);

    // 评分最高的 10 道菜
    List<Dish> getTop10Dishes();

    // 某道菜的所有评价（按时间倒序）
    List<Review> getReviewsByDishId(Long dishId);

    // 判断某个用户是否已经评价过某道菜
    boolean hasReviewed(Long dishId, Long userId);

    // ===== 管理员功能 =====

    // 新增菜品（含图片上传）
    boolean addDish(Dish dish, MultipartFile imageFile);

    // 修改菜品（imageFile 为空表示沿用原图）
    boolean updateDish(Dish dish, MultipartFile imageFile);

    // 删除菜品（连同该菜品的所有评价一起删除）
    boolean deleteDish(Long dishId);

    // 后台菜品列表：按销售档位筛选 + 排序
    // level 取值：null/空=全部，"bad"=滞销(0~3分)，"normal"=正常(3~4分)，"good"=畅销(4~5分)
    List<Dish> listDishesForAdmin(String level, String sortBy);

    // 带分页和搜索的查询
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<Dish> searchDishesWithPage(
            String keyword, Double minRating, com.baomidou.mybatisplus.extension.plugins.pagination.Page<Dish> page);
}
