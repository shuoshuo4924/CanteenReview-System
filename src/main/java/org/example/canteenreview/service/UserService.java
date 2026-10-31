package org.example.canteenreview.service;

import org.example.canteenreview.entity.User;

/**
 * 用户业务接口：Controller 只跟它打交道，不碰 Mapper
 * 注意：不继承 IService，只依赖 mybatis-plus-core
 */
public interface UserService {

    /**
     * 全站统一的密保问题：所有账号共用同一个问题，注册时只填答案。
     * 想换问题只改这一行（已注册用户仍保留当时存下的问题文本）。
     */
    String SECURITY_QUESTION = "你小学时最好的朋友叫什么名字？";

    // 注册成功返回 true；用户名已存在返回 false
    boolean register(String username, String password, String securityAnswer);

    // 登录成功返回 User 对象；账号或密码错误返回 null
    User login(String username, String password);

    // 按用户名查询，不存在返回 null；返回的对象已清空密码和密保答案
    User findByUsername(String username);

    // 密保答案正确才重置密码：成功返回 true，用户不存在/未设密保/答案错误返回 false
    boolean resetPassword(String username, String securityAnswer, String newPassword);
}
