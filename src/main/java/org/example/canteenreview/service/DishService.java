package org.example.canteenreview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.canteenreview.entity.Dish;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 菜品域业务：只负责菜品本身的查询与增删改。
 * 评价相关（提交评价、平均分回写）在 ReviewService。
 */
public interface DishService {

    // 按 id 查单个菜品
    Dish getDishById(Long id);

    // 评分最高的 10 道菜
    List<Dish> getTop10Dishes();

    // 带分页和搜索的查询
    Page<Dish> searchDishesWithPage(String keyword, Double minRating, Page<Dish> page);

    // 新增菜品（含图片上传）
    boolean addDish(Dish dish, MultipartFile imageFile);

    // 修改菜品（imageFile 为空表示沿用原图）
    boolean updateDish(Dish dish, MultipartFile imageFile);

    // 删除菜品（连同该菜品的所有评价一起删除）
    boolean deleteDish(Long dishId);

    // 后台菜品列表：按销售档位筛选 + 排序
    // level 取值：null/空=全部，"bad"=滞销(0~3分)，"normal"=正常(3~4分)，"good"=畅销(4~5分)
    List<Dish> listDishesForAdmin(String level, String sortBy);

    /** 取还没有健康建议的菜品（按 id 升序，最多 limit 条），供 AI 分批生成 */
    List<Dish> listDishesWithoutHealthTip(int limit);

    /** 还没有健康建议的菜品数量 */
    long countDishesWithoutHealthTip();

    /** 只更新健康建议字段（返回 false 表示菜品不存在或建议为空） */
    boolean updateHealthTip(Long dishId, String healthTip);
}
