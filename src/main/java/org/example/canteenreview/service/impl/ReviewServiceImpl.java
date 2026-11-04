package org.example.canteenreview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.mapper.DishMapper;
import org.example.canteenreview.mapper.ReviewMapper;
import org.example.canteenreview.service.FileStorageService;
import org.example.canteenreview.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评价域实现：提交评价、查询评价、防重复、回写菜品平均分。
 */
@Service
public class ReviewServiceImpl implements ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewServiceImpl.class);

    // 与 reviews.comment 列（VARCHAR(500)）保持一致
    private static final int MAX_COMMENT_LENGTH = 500;

    @Autowired
    private ReviewMapper reviewMapper;

    // 评价提交后需要把平均分回写到菜品表
    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private FileStorageService fileStorageService;

    @Override
    @Transactional // 插评价 + 重算平均分放在同一事务，避免一半成功导致数据不一致
    public boolean submitReview(Review review, MultipartFile imageFile) {
        if (review == null || review.getDishId() == null || review.getUserId() == null) {
            return false;
        }

        // ===== 0. 字段兜底校验：数据非法就提前返回，而不是让数据库抛 SQLException =====
        Integer rating = review.getRating();
        if (rating == null || rating < 1 || rating > 5) {
            log.warn("评分非法，已拒绝：dishId={}, rating={}", review.getDishId(), rating);
            return false;
        }
        String comment = review.getComment();
        if (comment != null && comment.trim().length() > MAX_COMMENT_LENGTH) {
            log.warn("评价内容超长，已拒绝：dishId={}, length={}", review.getDishId(), comment.trim().length());
            return false;
        }
        // 菜品可能已被管理员删除，避免写出一条指向不存在菜品的评价
        if (dishMapper.selectById(review.getDishId()) == null) {
            log.warn("菜品不存在，评价被拒绝：dishId={}", review.getDishId());
            return false;
        }

        // ===== 1. 防重复评价拦截 =====
        if (hasReviewed(review.getDishId(), review.getUserId())) {
            return false;
        }

        // ===== 2. 处理图片上传（文件名安全化 + 扩展名白名单由 FileStorageService 负责）=====
        String imageUrl = fileStorageService.saveImage(imageFile);
        if (imageUrl != null) {
            review.setImageUrl(imageUrl);
        }

        // ===== 3. 保存评价 =====
        review.setCreateTime(LocalDateTime.now());
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发时数据库唯一索引兜底拦截，同样按"重复评价"处理；
            // 刚存下的晒图要清掉，否则这条评价没入库，图片却留在磁盘上
            fileStorageService.deleteImage(review.getImageUrl());
            return false;
        }

        // ===== 4. 重算平均分 =====
        updateAvgRating(review.getDishId());

        return true;
    }

    @Override
    public List<Review> getReviewsByDishId(Long dishId) {
        if (dishId == null) {
            return List.of();
        }
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.eq("dish_id", dishId).orderByDesc("create_time");
        return reviewMapper.selectList(wrapper);
    }

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
    public void deleteByDishId(Long dishId) {
        if (dishId == null) {
            return;
        }
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.eq("dish_id", dishId);
        // 先按记录删晒图文件，再删记录：记录一删就找不到图片路径了
        for (Review review : reviewMapper.selectList(wrapper)) {
            fileStorageService.deleteImage(review.getImageUrl());
        }
        reviewMapper.delete(wrapper);
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
        log.debug("菜品 {} 平均分更新为 {}", dishId, dish.getAvgRating());
    }
}
