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
DROP TABLE IF EXISTS suggestions;
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
    -- 必须 NOT NULL：MySQL 唯一索引把 NULL 视为互不相等，canteen 为 NULL 时同名菜可以无限重复插入
    canteen    VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '所属食堂',
    avg_rating DOUBLE                DEFAULT 0 COMMENT '平均评分（冗余字段，评价后回写）',
    image_url  VARCHAR(255)          DEFAULT NULL COMMENT '菜品图片 URL',
    price      DOUBLE                DEFAULT NULL COMMENT '价格',
    PRIMARY KEY (id),
    -- 业务唯一键：同一食堂下菜名不重复；不同食堂的同名菜是两条合法记录。
    -- price 绝不能进唯一键，调价是常态，否则"改价=新增一道菜"，评价历史会被拆断
    UNIQUE KEY uk_dish_name_canteen (name, canteen)
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

-- -------------------------------------------------------------
-- 建议箱表
-- 说明：不建外键，用户被删也不影响历史留言（author 冗余存一份）；
--       不收集联系方式，只留类型和内容。
-- -------------------------------------------------------------
CREATE TABLE suggestions (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '提交人 id',
    author      VARCHAR(50)           DEFAULT NULL COMMENT '提交人显示名（冗余）',
    type        VARCHAR(20)  NOT NULL DEFAULT 'OTHER' COMMENT '类型：DISH / CANTEEN / SYSTEM / OTHER',
    content     VARCHAR(500) NOT NULL COMMENT '建议内容',
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待处理 / REPLIED 已回复',
    reply       VARCHAR(500)          DEFAULT NULL COMMENT '管理员回复',
    create_time DATETIME              DEFAULT NULL COMMENT '提交时间',
    reply_time  DATETIME              DEFAULT NULL COMMENT '回复时间',
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='建议箱表';

-- 已有数据库不重建时，单独执行下面这条即可（不会清空数据）：
-- CREATE TABLE IF NOT EXISTS suggestions (
--     id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
--     author VARCHAR(50), type VARCHAR(20) NOT NULL DEFAULT 'OTHER',
--     content VARCHAR(500) NOT NULL,
--     status VARCHAR(20) NOT NULL DEFAULT 'PENDING', reply VARCHAR(500),
--     create_time DATETIME, reply_time DATETIME,
--     PRIMARY KEY (id), KEY idx_status (status), KEY idx_user_id (user_id)
-- ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='建议箱表';
