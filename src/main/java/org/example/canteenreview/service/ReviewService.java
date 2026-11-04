package org.example.canteenreview.service;

import org.example.canteenreview.entity.Review;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 评价域业务：提交评价、查评价、防重复评价、回写平均分。
 * 与菜品自身的增删改互不干涉。
 */
public interface ReviewService {

    // 提交评价（含图片上传 + 自动重算菜品平均分），重复评价返回 false
    boolean submitReview(Review review, MultipartFile imageFile);

    // 某道菜的所有评价（按时间倒序）
    List<Review> getReviewsByDishId(Long dishId);

    // 判断某个用户是否已经评价过某道菜
    boolean hasReviewed(Long dishId, Long userId);

    // 删除某道菜下的所有评价（菜品被删除时调用）
    void deleteByDishId(Long dishId);
}
