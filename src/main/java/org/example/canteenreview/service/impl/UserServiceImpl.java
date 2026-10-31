package org.example.canteenreview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.mapper.UserMapper;
import org.example.canteenreview.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户业务实现类：密码加密、校验等逻辑都写在这里
 * 不继承 ServiceImpl，改为直接注入 Mapper 调用
 */
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    // 密码加密工具（明文密码绝不存数据库）
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public boolean register(String username, String password) {
        // 1. 用户名是否已存在
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username);
        if (userMapper.selectCount(wrapper) > 0) {
            return false;
        }

        // 2. 组装用户（密码加密后入库）
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER");

        return userMapper.insert(user) > 0;
    }

    @Override
    public User login(String username, String password) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username);

        // 用 selectList 而不是 selectOne，避免同名时出现 TooManyResults 异常
        List<User> users = userMapper.selectList(wrapper);
        if (users.isEmpty()) {
            return null;
        }

        User user = users.get(0);
        // 拿明文密码和数据库里的密文做比对
        if (passwordEncoder.matches(password, user.getPassword())) {
            return user;
        }
        return null;
    }
}
