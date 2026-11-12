package org.example.canteenreview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.canteenreview.common.SensitiveWordFilter;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.ReviewAuditLog;
import org.example.canteenreview.mapper.DishMapper;
import org.example.canteenreview.mapper.ReviewAuditLogMapper;
import org.example.canteenreview.mapper.ReviewMapper;
import org.example.canteenreview.service.FileStorageService;
import org.example.canteenreview.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    // 菜品详情页一次最多展示多少条评价（不分页，但要防止整页被拖垮）
    private static final int MAX_REVIEWS_PER_DISH = 200;

    // 后台审核列表一次最多拉多少条
    private static final int MAX_ADMIN_REVIEWS = 300;

    // 先审后发开关：true 时新评价进入待审核，后台通过后才在前台展示
    @Value("${app.review-audit:true}")
    private boolean auditEnabled;

    @Autowired
    private ReviewMapper reviewMapper;

    // 审核动作要留痕：谁、什么时候、对哪条评价做了什么
    @Autowired
    private ReviewAuditLogMapper auditLogMapper;

    // 评价提交后需要把平均分回写到菜品表
    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private FileStorageService fileStorageService;

    // 评论内容过滤：广告导流、刷单、违规交易等一律拦在入库前
    @Autowired
    private SensitiveWordFilter sensitiveWordFilter;

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
        // 敏感词兜底：Controller 会先拦一次并给出提示，这里再挡一遍，
        // 防止以后新增的调用方（批量导入、后台补录）绕过 Controller 直接把脏内容写进库
        SensitiveWordFilter.GuardResult guard = sensitiveWordFilter.check(comment);
        if (guard.hit()) {
            log.warn("评价内容被拦截，已拒绝：category={}, dishId={}, userId={}",
                    guard.category(), review.getDishId(), review.getUserId());
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
        // 先审后发：默认落成待审核，后台点"通过"后才出现在前台；
        // 关掉审核开关时直接置为已通过，行为和以前一致
        review.setStatus(auditEnabled ? STATUS_PENDING : STATUS_APPROVED);
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
        // 只展示审核通过的评价：待审核的内容用户自己能看到"审核中"提示，其他人看不到
        wrapper.eq("dish_id", dishId).eq("status", STATUS_APPROVED).orderByDesc("create_time");
        // 详情页暂不分页，但必须有上限：否则单道菜评价上万条时，一次请求会把整页拖垮
        Page<Review> page = new Page<>(1, MAX_REVIEWS_PER_DISH, false);
        return reviewMapper.selectPage(page, wrapper).getRecords();
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

    // ================= 后台审核 =================

    @Override
    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    @Override
    public List<Review> listReviewsForAdmin(Integer status) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        Page<Review> page = new Page<>(1, MAX_ADMIN_REVIEWS, false);
        return reviewMapper.selectPage(page, wrapper).getRecords();
    }

    @Override
    public long countPending() {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_PENDING);
        return reviewMapper.selectCount(wrapper);
    }

    @Override
    @Transactional
    public boolean approveReview(Long reviewId, String operator) {
        Review review = reviewId == null ? null : reviewMapper.selectById(reviewId);
        if (review == null) {
            return false;
        }
        if (Integer.valueOf(STATUS_APPROVED).equals(review.getStatus())) {
            return true; // 已经是已通过状态，重复点不报错
        }
        Review update = new Review();
        update.setId(reviewId);
        update.setStatus(STATUS_APPROVED);
        if (reviewMapper.updateById(update) <= 0) {
            return false;
        }
        // 通过后这条评价才参与平均分
        updateAvgRating(review.getDishId());
        writeAuditLog(review, ReviewAuditLog.ACTION_APPROVE, operator);
        log.info("评价审核通过：reviewId={}, dishId={}, author={}, operator={}",
                reviewId, review.getDishId(), review.getAuthor(), operator);
        return true;
    }

    @Override
    @Transactional
    public boolean rejectReview(Long reviewId, String operator) {
        Review review = reviewId == null ? null : reviewMapper.selectById(reviewId);
        if (review == null) {
            return false;
        }
        // 先写日志再删：删完就只剩 id，内容快照再也拿不到了
        writeAuditLog(review, ReviewAuditLog.ACTION_REJECT, operator);
        // 拒绝就是物理删除：唯一索引 uk_dish_user 还在，
        // 留着拒绝记录用户就永远无法重新评价这道菜了
        reviewMapper.deleteById(reviewId);
        fileStorageService.deleteImage(review.getImageUrl());
        updateAvgRating(review.getDishId());
        log.info("评价审核拒绝并删除：reviewId={}, dishId={}, author={}, operator={}, 内容摘要={}",
                reviewId, review.getDishId(), review.getAuthor(), operator, shorten(review.getComment()));
        return true;
    }

    @Override
    public List<ReviewAuditLog> listAuditLogs(int limit) {
        QueryWrapper<ReviewAuditLog> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("create_time").orderByDesc("id");
        Page<ReviewAuditLog> page = new Page<>(1, Math.max(1, limit), false);
        return auditLogMapper.selectPage(page, wrapper).getRecords();
    }

    /** 把被审核评价的内容快照写进日志表，拒绝删除后仍可追溯 */
    private void writeAuditLog(Review review, String action, String operator) {
        ReviewAuditLog entry = new ReviewAuditLog();
        entry.setReviewId(review.getId());
        entry.setDishId(review.getDishId());
        entry.setAction(action);
        entry.setOperator(operator);
        entry.setAuthor(review.getAuthor());
        entry.setRating(review.getRating());
        entry.setComment(shortenLong(review.getComment(), 500));
        entry.setImageUrl(review.getImageUrl());
        entry.setCreateTime(LocalDateTime.now());
        auditLogMapper.insert(entry);
    }

    /** 日志表的 comment 列是 VARCHAR(500)，超长会被数据库截断报错，这里先截 */
    private static String shortenLong(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 日志里只留内容摘要，避免整段评价刷屏 */
    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 30 ? trimmed : trimmed.substring(0, 30) + "...";
    }

    // 重算平均分（用 selectMaps 接收，避免 AVG 小数被截断）
    private void updateAvgRating(Long dishId) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        // 只有审核通过的评价才算分，否则待审/刷分内容会直接影响榜单
        wrapper.select("AVG(rating) as avgRating").eq("dish_id", dishId).eq("status", STATUS_APPROVED);

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
