package org.example.canteenreview.service;

import org.example.canteenreview.entity.User;

import java.util.List;

/**
 * 用户业务接口：Controller 只跟它打交道，不碰 Mapper
 * 注意：不继承 IService，只依赖 mybatis-plus-core
 */
public interface UserService {

    /**
     * 默认密保问题：老账号（以及注册时没选问题的情况）都存这一条。
     * 想换默认问题只改这一行（已注册用户仍保留当时存下的问题文本）。
     */
    String SECURITY_QUESTION = "你小学时最好的朋友叫什么名字？";

    /**
     * 注册时可选的密保问题。
     *
     * 以前全站共用一个问题，所有人的答案空间是同一个，
     * 泄漏一条就能套到别人身上；改成自选后，每个账号暴露的信息彼此独立。
     * 服务端只接受这里列出的问题，避免被塞入任意文本。
     */
    List<String> SECURITY_QUESTIONS = List.of(
            SECURITY_QUESTION,
            "你第一次独自出远门是去哪座城市？",
            "你养的第一只宠物叫什么名字？",
            "你最喜欢的一本课外书叫什么？",
            "你高中班主任的姓氏是什么？",
            "你最拿手的一道菜是什么？"
    );

    // 注册成功返回 true；用户名已存在返回 false
    boolean register(String username, String password, String securityQuestion, String securityAnswer);

    // 登录成功返回 User 对象；账号或密码错误返回 null
    User login(String username, String password);

    // 按用户名查询，不存在返回 null；返回的对象已清空密码和密保答案
    User findByUsername(String username);

    // 密保答案正确才重置密码：成功返回 true，用户不存在/未设密保/答案错误返回 false
    boolean resetPassword(String username, String securityAnswer, String newPassword);
}
