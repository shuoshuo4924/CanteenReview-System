package org.example.canteenreview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.canteenreview.entity.User;
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
