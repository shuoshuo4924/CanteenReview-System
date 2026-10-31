package org.example.canteenreview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.mapper.DishMapper;
import org.example.canteenreview.mapper.ReviewMapper;
import org.example.canteenreview.service.DishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 菜品业务实现类：所有业务逻辑都写在这里，Controller 不再重复实现
 */
@Service
public class DishServiceImpl implements DishService {

    private static final Logger log = LoggerFactory.getLogger(DishServiceImpl.class);

    // 允许上传的图片扩展名白名单（统一按小写比较）
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    // 中文数字，用于把「1食堂」归一成「一食堂」
    private static final String[] CN_DIGITS = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};

    // 图片保存目录（从配置读取，避免在多处硬编码路径）
    @Value("${app.image-dir}")
    private String imageDir;

    // 浏览器访问图片的 URL 前缀
    @Value("${app.image-url-prefix}")
    private String imageUrlPrefix;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private ReviewMapper reviewMapper;

    @Override
    public List<Dish> listAllDishes() {
        return dishMapper.selectList(null);
    }

    @Override
    public List<Dish> listDishesForAdmin(String level, String sortBy) {
        QueryWrapper<Dish> wrapper = new QueryWrapper<>();
        // 按档位筛选（区间不重不漏）
        // bad=滞销(0~3分, 即 <3)  normal=正常(3~4分, 即 >=3 且 <4)  good=畅销(4~5分, 即 >=4)
        if (level != null && !level.isBlank()) {
            if ("bad".equals(level)) {
                wrapper.lt("avg_rating", 3);
            } else if ("normal".equals(level)) {
                wrapper.ge("avg_rating", 3).lt("avg_rating", 4);
            } else if ("good".equals(level)) {
                wrapper.ge("avg_rating", 4);
            }
        }
        // 排序：默认评分降序；可选价格升/降、评分升序
        if ("price_asc".equals(sortBy)) {
            wrapper.orderByAsc("price");
        } else if ("price_desc".equals(sortBy)) {
            wrapper.orderByDesc("price");
        } else if ("rating_asc".equals(sortBy)) {
            wrapper.orderByAsc("avg_rating");
        } else {
            // 默认：评分从高到低
            wrapper.orderByDesc("avg_rating");
        }
        return dishMapper.selectList(wrapper);
    }

    @Override
    public List<Dish> searchDishes(String keyword, Double minRating) {
        QueryWrapper<Dish> wrapper = new QueryWrapper<>();
        // 关键词模糊匹配：菜名 或 食堂
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("name", kw).or().like("canteen", kw));
        }
        // 最低评分筛选
        if (minRating != null) {
            wrapper.ge("avg_rating", minRating);
        }
        // 按评分从高到低排序，没有评分的排后面
        wrapper.orderByDesc("avg_rating");
        return dishMapper.selectList(wrapper);
    }

    @Override
    public Dish getDishById(Long id) {
        return dishMapper.selectById(id);
    }

    @Override
    public List<Review> getReviewsByDishId(Long dishId) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.eq("dish_id", dishId).orderByDesc("create_time");
        return reviewMapper.selectList(wrapper);
    }

    // 判断某个用户是否已经评价过某道菜（页面控制表单显示 + 提交时拦截都靠它）
    @Override
    public boolean hasReviewed(Long dishId, Long userId) {
        if (dishId == null || userId == null) {
            return false;
        }
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.eq("dish_id", dishId).eq("user_id", userId);
        return reviewMapper.selectCount(wrapper) > 0;
    }

    @Override
    @Transactional // 插评价 + 重算平均分放在同一事务，避免一半成功导致数据不一致
    public boolean submitReview(Review review, MultipartFile imageFile) {

        // ===== 1. 防重复评价拦截 =====
        if (hasReviewed(review.getDishId(), review.getUserId())) {
            return false;
        }

        // ===== 2. 处理图片上传（文件名安全化 + 扩展名白名单）=====
        if (imageFile != null && !imageFile.isEmpty()) {
            String imageUrl = saveImage(imageFile);
            if (imageUrl != null) {
                review.setImageUrl(imageUrl);
            }
        }

        // ===== 3. 保存评价 =====
        review.setCreateTime(LocalDateTime.now());
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发时数据库唯一索引兜底拦截，同样按"重复评价"处理
            return false;
        }

        // ===== 4. 重算平均分 =====
        updateAvgRating(review.getDishId());

        return true;
    }

    @Override
    public List<Dish> getTop10Dishes() {
        QueryWrapper<Dish> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("avg_rating").last("LIMIT 10");
        return dishMapper.selectList(wrapper);
    }

    /**
     * 保存上传的图片，成功返回可访问的 URL，失败返回 null。
     * 文件名由 时间戳 + UUID + 原扩展名 重新生成，绝不直接使用用户文件名，防止路径穿越等问题。
     */
    private String saveImage(MultipartFile imageFile) {
        String extension = extractExtension(imageFile.getOriginalFilename());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            log.warn("拒绝上传非图片文件：{}", imageFile.getOriginalFilename());
            return null;
        }
        try {
            File dir = new File(imageDir);
            if (!dir.exists() && !dir.mkdirs()) {
                log.error("无法创建图片目录：{}", dir.getAbsolutePath());
                return null;
            }

            String newFileName = System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().replace("-", "") + "." + extension;
            imageFile.transferTo(new File(dir, newFileName));

            return imageUrlPrefix + newFileName;
        } catch (Exception e) {
            log.error("图片保存失败", e);
            return null;
        }
    }

    /**
     * 从原始文件名中截取扩展名（小写、不含点），取不到返回 null。
     */
    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return null;
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 食堂名归一化：删掉所有空白、去掉「第」前缀、把「1食堂」这类写法换成「一食堂」。
     * 这样「第一食堂」「1食堂」「 一食堂 」都会落到同一个值，
     * 唯一键 uk_dish_name_canteen 才拦得住"同一食堂的同名菜重复录入"。
     * 传入 null 时原样返回 null，表示"本次不修改食堂"（编辑时用于保持原值）。
     */
    private String normalizeCanteen(String canteen) {
        if (canteen == null) {
            return null;
        }
        String value = canteen.trim().replaceAll("\\s+", "");
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("第")) {
            value = value.substring(1);
        }
        if (value.length() >= 2 && value.endsWith("食堂")) {
            char first = value.charAt(0);
            if (first >= '1' && first <= '9') {
                value = CN_DIGITS[first - '1'] + value.substring(1);
            }
        }
        return value;
    }

    // ===== 管理员功能 =====

    @Override
    @Transactional
    public boolean addDish(Dish dish, MultipartFile imageFile) {
        if (dish == null || dish.getName() == null || dish.getName().isBlank()) {
            return false;
        }
        // 菜名 / 食堂名统一清洗，避免同一道菜因写法不同绕过唯一键
        dish.setName(dish.getName().trim());
        String canteen = normalizeCanteen(dish.getCanteen());
        dish.setCanteen(canteen == null ? "" : canteen); // canteen 列为 NOT NULL
        // 新增菜品默认 0 分，避免前端没填导致 NPE / 排行异常
        if (dish.getAvgRating() == null) {
            dish.setAvgRating(0.0);
        }
        if (imageFile != null && !imageFile.isEmpty()) {
            String imageUrl = saveImage(imageFile);
            if (imageUrl != null) {
                dish.setImageUrl(imageUrl);
            }
        }
        dish.setId(null); // 防止前端传 id 覆盖
        try {
            return dishMapper.insert(dish) > 0;
        } catch (DuplicateKeyException e) {
            // 唯一键 uk_dish_name_canteen 兜底：该食堂下已有同名菜品
            log.warn("菜品重复，已拦截：{}（{}）", dish.getName(), dish.getCanteen());
            return false;
        }
    }

    @Override
    @Transactional
    public boolean updateDish(Dish dish, MultipartFile imageFile) {
        if (dish == null || dish.getId() == null) {
            return false;
        }
        // 改名/改食堂同样要清洗，否则会绕过唯一键
        if (dish.getName() != null) {
            dish.setName(dish.getName().trim());
        }
        // 传 null 表示本次不改食堂，交给 MyBatis-Plus 忽略该字段
        dish.setCanteen(normalizeCanteen(dish.getCanteen()));
        // 上传了新图才替换；否则保持数据库里的原图不变
        if (imageFile != null && !imageFile.isEmpty()) {
            String imageUrl = saveImage(imageFile);
            if (imageUrl != null) {
                dish.setImageUrl(imageUrl);
            }
        } else {
            // 不更新图片字段：置空会被 MyBatis-Plus 的 updateById 忽略（默认策略非空更新），保留原值
            dish.setImageUrl(null);
        }
        // avgRating 由评价动态重算，不在编辑时手动改
        dish.setAvgRating(null);
        try {
            return dishMapper.updateById(dish) > 0;
        } catch (DuplicateKeyException e) {
            // 改成的新名字在该食堂下已存在
            log.warn("菜品重复，更新被拦截：{}（{}）", dish.getName(), dish.getCanteen());
            return false;
        }
    }

    @Override
    @Transactional
    public boolean deleteDish(Long dishId) {
        if (dishId == null) {
            return false;
        }
        // 先删该菜品下的所有评价，再删菜品本身
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.eq("dish_id", dishId);
        reviewMapper.delete(wrapper);
        return dishMapper.deleteById(dishId) > 0;
    }

    // 重算平均分（用 selectMaps 接收，避免 AVG 小数被截断）
    private void updateAvgRating(Long dishId) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.select("AVG(rating) as avgRating").eq("dish_id", dishId);

        List<Map<String, Object>> maps = reviewMapper.selectMaps(wrapper);
        if (maps.isEmpty()) {
            return;
        }

        Object avg = maps.get(0).get("avgRating");
        if (avg == null) {
            return;
        }

        double avgRating = Double.parseDouble(avg.toString());
        Dish dish = new Dish();
        dish.setId(dishId);
        dish.setAvgRating(Math.round(avgRating * 10.0) / 10.0);
        dishMapper.updateById(dish);
    }
    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Dish> searchDishesWithPage(
            String keyword, Double minRating, com.baomidou.mybatisplus.extension.plugins.pagination.Page<Dish> page) {

        QueryWrapper<Dish> wrapper = new QueryWrapper<>();

        // 搜索条件：菜名 或 食堂，模糊匹配
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("name", kw).or().like("canteen", kw));
        }
        if (minRating != null) {
            wrapper.ge("avg_rating", minRating);
        }

        // 按评分降序排列；必须再加一个唯一键做兜底排序，
        // 否则同分菜品的返回顺序不稳定，翻页时会出现「重复 + 漏项」
        wrapper.orderByDesc("avg_rating").orderByAsc("id");

        // 关键：本类没有继承 ServiceImpl，不能用 this.page(...)
        // 改用 BaseMapper 自带的 selectPage，配合 PaginationInnerInterceptor 自动执行 COUNT + LIMIT
        return dishMapper.selectPage(page, wrapper);
    }

}
