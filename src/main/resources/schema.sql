-- =============================================================
-- 吃啥饭 · 校园食堂评价系统  数据库建表脚本
-- 数据库：MySQL 8.x
-- 说明：可直接执行本脚本初始化数据库结构；如需重建，会先删除旧表。
-- =============================================================

CREATE DATABASE IF NOT EXISTS canteen_review
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE canteen_review;

-- 先删从表，再删主表，避免外键/顺序问题
DROP TABLE IF EXISTS reviews;
DROP TABLE IF EXISTS dish;
DROP TABLE IF EXISTS `user`;

-- -------------------------------------------------------------
-- 用户表
-- -------------------------------------------------------------
CREATE TABLE `user` (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username          VARCHAR(50)  NOT NULL COMMENT '用户名',
    password          VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密后的密文）',
    role              VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER / ADMIN',
    security_question VARCHAR(200)          DEFAULT NULL COMMENT '密保问题',
    security_answer   VARCHAR(200)          DEFAULT NULL COMMENT '密保答案（BCrypt 加密后的密文）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- 已有数据库不重建表时，单独执行下面这条升级（新增密保字段）：
-- ALTER TABLE `user`
--     ADD COLUMN security_question VARCHAR(200) DEFAULT NULL COMMENT '密保问题' AFTER `role`,
--     ADD COLUMN security_answer   VARCHAR(200) DEFAULT NULL COMMENT '密保答案（BCrypt 密文）' AFTER security_question;

-- -------------------------------------------------------------
-- 菜品表
-- -------------------------------------------------------------
CREATE TABLE dish (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(100) NOT NULL COMMENT '菜名',
    canteen    VARCHAR(50)           DEFAULT NULL COMMENT '所属食堂',
    avg_rating DOUBLE                DEFAULT 0 COMMENT '平均评分（冗余字段，评价后回写）',
    image_url  VARCHAR(255)          DEFAULT NULL COMMENT '菜品图片 URL',
    price      DOUBLE                DEFAULT NULL COMMENT '价格',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品表';

-- -------------------------------------------------------------
-- 评价表
-- 注意：uk_dish_user 唯一索引是"防重复评价"的最终兜底，
--       保证同一用户对同一菜品只能评价一次（并发下也生效）。
-- -------------------------------------------------------------
CREATE TABLE reviews (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dish_id     BIGINT       NOT NULL COMMENT '菜品 id',
    user_id     BIGINT       NOT NULL COMMENT '评价人 id',
    rating      INT          NOT NULL COMMENT '评分 1~5',
    image_url   VARCHAR(255)          DEFAULT NULL COMMENT '晒图 URL',
    comment     VARCHAR(500)          DEFAULT NULL COMMENT '评价内容',
    author      VARCHAR(50)           DEFAULT NULL COMMENT '评价人显示名',
    create_time DATETIME              DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dish_user (dish_id, user_id),
    KEY idx_dish_id (dish_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评价表';
