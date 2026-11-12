package org.example.canteenreview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价审核日志。
 * 拒绝会把评价物理删除，所以这里存一份内容快照，
 * 事后追查"这条评价为什么被删、是谁删的"时才查得到。
 */
@Data
@TableName("review_audit_log")
public class ReviewAuditLog {

    /** 审核通过 */
    public static final String ACTION_APPROVE = "APPROVE";

    /** 审核拒绝（评价已被删除） */
    public static final String ACTION_REJECT = "REJECT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reviewId;

    private Long dishId;

    private String action;

    private String operator;

    /** 评价人显示名 */
    private String author;

    private Integer rating;

    /** 评价内容快照 */
    private String comment;

    private String imageUrl;

    private LocalDateTime createTime;
}
