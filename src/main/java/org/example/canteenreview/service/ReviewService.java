package org.example.canteenreview.service;

import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.ReviewAuditLog;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 评价域业务：提交评价、查评价、防重复评价、回写平均分、后台审核。
 * 与菜品自身的增删改互不干涉。
 */
public interface ReviewService {

    /** 待审核状态：先审后发开启时，新评价先落到这个状态 */
    int STATUS_PENDING = 0;

    /** 已通过状态：只有这个状态的评价才会出现在前台、才参与平均分 */
    int STATUS_APPROVED = 1;

    // 提交评价（含图片上传 + 自动重算菜品平均分），重复评价返回 false
    boolean submitReview(Review review, MultipartFile imageFile);

    // 某道菜的所有评价（按时间倒序，只返回已通过的）
    List<Review> getReviewsByDishId(Long dishId);

    // 判断某个用户是否已经评价过某道菜
    boolean hasReviewed(Long dishId, Long userId);

    // 删除某道菜下的所有评价（菜品被删除时调用）
    void deleteByDishId(Long dishId);

    // ================= 后台审核 =================

    /** 是否开启先审后发（app.review-audit） */
    boolean isAuditEnabled();

    /** 后台列表：status 为 null 时查全部，按提交时间倒序 */
    List<Review> listReviewsForAdmin(Integer status);

    /** 待审核条数，用于后台列表页的提示 */
    long countPending();

    /** 审核通过：状态置为已通过，重算该菜的平均分，并写一条审核日志 */
    boolean approveReview(Long reviewId, String operator);

    /** 审核不通过：删除记录与晒图（用户可重新评价），并把快照写进审核日志 */
    boolean rejectReview(Long reviewId, String operator);

    /** 最近的审核记录，给后台页面展示 */
    List<ReviewAuditLog> listAuditLogs(int limit);
}
