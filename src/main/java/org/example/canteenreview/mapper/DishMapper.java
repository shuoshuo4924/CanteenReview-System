package org.example.canteenreview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.canteenreview.entity.Dish;
import org.apache.ibatis.annotations.Mapper;

@Mapper // 告诉系统这是管数据库的
public interface DishMapper extends BaseMapper<Dish> {
    // 不用写任何代码！MyBatis-Plus 已经自动帮你写好了增删改查所有 SQL！
}