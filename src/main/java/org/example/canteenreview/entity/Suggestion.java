package org.example.canteenreview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 建议箱留言：只保留类型、内容、作者、时间和官方回复。
 * 不收集联系方式，避免额外的隐私负担。
 */
@Data
@TableName("suggestions")
public class Suggestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提交人 id（用于「我的建议」，不需要外键也能工作） */
    private Long userId;

    /** 提交人显示名，冗余存一份，用户被删也不影响历史留言 */
    private String author;

    /** 类型码：DISH / CANTEEN / SYSTEM / OTHER，展示时转中文 */
    private String type;

    /** 建议正文（≤500 字，与列长度一致） */
    private String content;

    /** 状态码：PENDING 待处理 / REPLIED 已回复 */
    private String status;

    /** 管理员回复内容 */
    private String reply;

    private LocalDateTime createTime;

    private LocalDateTime replyTime;

    /** 展示用：类型码转中文（不是数据库字段，MyBatis-Plus 只认字段不认 getter） */
    public String getTypeLabel() {
        if (type == null) {
            return "其他";
        }
        return switch (type) {
            case "DISH" -> "菜品建议";
            case "CANTEEN" -> "食堂建议";
            case "SYSTEM" -> "系统问题";
            default -> "其他";
        };
    }

    /** 展示用：是否已被回复 */
    public boolean isReplied() {
        return "REPLIED".equals(status);
    }
}
