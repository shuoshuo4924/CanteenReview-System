package org.example.canteenreview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
@Data
@TableName("reviews")
public class Review {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dishId;
    private Long userId; // 新增：评价人 id（防重复评价用）
    private Integer rating;
    private String imageUrl;
    private String comment;
    private String author;
    private LocalDateTime createTime;
}
