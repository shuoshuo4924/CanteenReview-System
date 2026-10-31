package org.example.canteenreview.service;

import org.example.canteenreview.entity.User;

/**
 * 用户业务接口：Controller 只跟它打交道，不碰 Mapper
 * 注意：不继承 IService，只依赖 mybatis-plus-core
 */
public interface UserService {

    // 注册成功返回 true；用户名已存在返回 false
    boolean register(String username, String password);

    // 登录成功返回 User 对象；账号或密码错误返回 null
    User login(String username, String password);
}
