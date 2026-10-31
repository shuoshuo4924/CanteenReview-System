package org.example.canteenreview.entity;

import com.alibaba.excel.annotation.ExcelProperty;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("dish")
public class Dish {
    @TableId(type = IdType.AUTO)
    private Long id;
    @ExcelProperty("菜名")
    private String name;
    @ExcelProperty("食堂")
    private String canteen;
    private Double avgRating;
    @ExcelProperty("图片")
    private String imageUrl;
    @ExcelProperty("价格")
    private Double price;
}