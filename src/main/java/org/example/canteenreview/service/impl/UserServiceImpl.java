package org.example.canteenreview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.canteenreview.entity.User;
import org.example.canteenreview.mapper.UserMapper;
import org.example.canteenreview.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 用户业务实现类：密码加密、校验等逻辑都写在这里
 * 不继承 ServiceImpl，改为直接注入 Mapper 调用
 */
@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private UserMapper userMapper;

    // 密码加密工具（明文密码绝不存数据库）
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public boolean register(String username, String password, String securityAnswer) {
        // 0. 兜底校验：BCrypt 不接受 null 密码，username 也有 NOT NULL 约束
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        String name = username.trim();

        // 1. 用户名是否已存在
        if (existsByUsername(name)) {
            return false;
        }

        // 2. 组装用户（密码、密保答案都加密后入库，问题用全站统一的那个）
        User user = new User();
        user.setUsername(name);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER");
        user.setSecurityQuestion(SECURITY_QUESTION);
        user.setSecurityAnswer(passwordEncoder.encode(normalizeAnswer(securityAnswer)));

        // 3. 并发下「先查后插」会同时通过，靠 uk_username 唯一键兜底
        try {
            return userMapper.insert(user) > 0;
        } catch (DuplicateKeyException e) {
            log.warn("用户名重复，注册被拦截：{}", name);
            return false;
        }
    }

    @Override
    public User login(String username, String password) {
        User user = selectByUsername(username);
        if (user == null) {
            return null;
        }
        // 拿明文密码和数据库里的密文做比对
        if (passwordEncoder.matches(password, user.getPassword())) {
            return user;
        }
        return null;
    }

    @Override
    public User findByUsername(String username) {
        User user = selectByUsername(username);
        if (user == null) {
            return null;
        }
        // 对外只暴露必要信息，密文一律清掉
        user.setPassword(null);
        user.setSecurityAnswer(null);
        return user;
    }

    @Override
    public boolean resetPassword(String username, String securityAnswer, String newPassword) {
        User user = selectByUsername(username);
        if (user == null) {
            return false;
        }
        // 老账号可能没设过密保，这种只能联系管理员
        if (user.getSecurityAnswer() == null || user.getSecurityAnswer().isBlank()) {
            return false;
        }
        if (!passwordEncoder.matches(normalizeAnswer(securityAnswer), user.getSecurityAnswer())) {
            return false;
        }

        // 只更新密码字段，其他列保持原样
        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        return userMapper.updateById(update) > 0;
    }

    // 密保答案统一去空格 + 转小写后比较，避免大小写、首尾空格导致"明明答对了却不对"
    private String normalizeAnswer(String answer) {
        return answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
    }

    private boolean existsByUsername(String username) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username);
        return userMapper.selectCount(wrapper) > 0;
    }

    // 用 selectList 而不是 selectOne，避免同名时出现 TooManyResults 异常
    private User selectByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username.trim());
        List<User> users = userMapper.selectList(wrapper);
        return users.isEmpty() ? null : users.get(0);
    }
}
