package org.example.canteenreview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.canteenreview.entity.Suggestion;

@Mapper
public interface SuggestionMapper extends BaseMapper<Suggestion> {
    // 增删改查由 MyBatis-Plus 提供，无需手写 SQL
}
