package org.example.canteenreview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.mapper.DishMapper;
import org.example.canteenreview.service.DishService;
import org.example.canteenreview.service.FileStorageService;
import org.example.canteenreview.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 菜品域实现：只处理菜品表的读写。
 * 评价的增删、平均分回写在 ReviewServiceImpl；图片落盘在 FileStorageService。
 */
@Service
public class DishServiceImpl implements DishService {

    private static final Logger log = LoggerFactory.getLogger(DishServiceImpl.class);

    // 中文数字，用于把「1食堂」归一成「一食堂」
    private static final String[] CN_DIGITS = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private FileStorageService fileStorageService;

    @Override
    public Dish getDishById(Long id) {
        return id == null ? null : dishMapper.selectById(id);
    }

    @Override
    public List<Dish> getTop10Dishes() {
        QueryWrapper<Dish> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("avg_rating").last("LIMIT 10");
        return dishMapper.selectList(wrapper);
    }

    @Override
    public Page<Dish> searchDishesWithPage(String keyword, Double minRating, Page<Dish> page) {
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

        // 本类没有继承 ServiceImpl，不能用 this.page(...)，
        // 改用 BaseMapper 自带的 selectPage，配合 PaginationInnerInterceptor 执行 COUNT + LIMIT
        return dishMapper.selectPage(page, wrapper);
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
        String imageUrl = fileStorageService.saveImage(imageFile);
        if (imageUrl != null) {
            dish.setImageUrl(imageUrl);
        }
        dish.setId(null); // 防止前端传 id 覆盖
        boolean inserted;
        try {
            inserted = dishMapper.insert(dish) > 0;
        } catch (DuplicateKeyException e) {
            // 唯一键 uk_dish_name_canteen 兜底：该食堂下已有同名菜品
            log.warn("菜品重复，已拦截：{}（{}）", dish.getName(), dish.getCanteen());
            inserted = false;
        }
        if (!inserted) {
            // 没入库成功，刚落盘的图片就是孤儿文件，顺手清掉
            fileStorageService.deleteImage(imageUrl);
        }
        return inserted;
    }

    @Override
    @Transactional
    public boolean updateDish(Dish dish, MultipartFile imageFile) {
        if (dish == null || dish.getId() == null) {
            return false;
        }
        // 先取旧记录：换图成功后要删掉旧文件，所以得知道原图地址
        Dish old = dishMapper.selectById(dish.getId());
        if (old == null) {
            return false;
        }
        // 改名/改食堂同样要清洗，否则会绕过唯一键
        if (dish.getName() != null) {
            dish.setName(dish.getName().trim());
        }
        // 传 null 表示本次不改食堂，交给 MyBatis-Plus 忽略该字段
        dish.setCanteen(normalizeCanteen(dish.getCanteen()));

        // 上传了新图才替换；否则保持数据库里的原图不变
        String imageUrl = fileStorageService.saveImage(imageFile);
        if (imageUrl != null) {
            dish.setImageUrl(imageUrl);
        } else {
            // 置空会被 MyBatis-Plus 的 updateById 忽略（默认策略非空更新），保留原值
            dish.setImageUrl(null);
        }
        // avgRating 由评价动态重算，不在编辑时手动改
        dish.setAvgRating(null);
        try {
            boolean updated = dishMapper.updateById(dish) > 0;
            if (imageUrl != null) {
                // 更新成功才删旧图；更新失败则把刚存的新图清掉，避免留下孤儿文件
                if (updated) {
                    fileStorageService.deleteImage(old.getImageUrl());
                } else {
                    fileStorageService.deleteImage(imageUrl);
                }
            }
            return updated;
        } catch (DuplicateKeyException e) {
            // 改成的新名字在该食堂下已存在
            log.warn("菜品重复，更新被拦截：{}（{}）", dish.getName(), dish.getCanteen());
            fileStorageService.deleteImage(imageUrl);
            return false;
        }
    }

    @Override
    @Transactional
    public boolean deleteDish(Long dishId) {
        if (dishId == null) {
            return false;
        }
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) {
            return false;
        }
        // 先删该菜品下的所有评价（连同晒图，由评价域负责），再删菜品本身
        reviewService.deleteByDishId(dishId);
        boolean deleted = dishMapper.deleteById(dishId) > 0;
        if (deleted) {
            // 记录删掉了，图片文件也一起清掉，否则会一直堆在磁盘上
            fileStorageService.deleteImage(dish.getImageUrl());
        }
        return deleted;
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
}
