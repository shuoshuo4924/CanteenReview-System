package org.example.canteenreview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.canteenreview.entity.Review;
import org.apache.ibatis.annotations.Mapper;

@Mapper                                    // ① 别忘这个注解，否则 Spring 找不到它
public interface ReviewMapper extends BaseMapper<Review> {   // ② 泛型换成 Review
    // 里面什么都不用写
}
