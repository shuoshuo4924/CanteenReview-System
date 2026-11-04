# 吃啥饭 · 校园食堂评价系统 🍚

一个基于 Spring Boot 的校园食堂菜品评价 Web 应用。同学可以浏览食堂菜品、查看人气榜、给菜品打分并晒图评价，帮助大家"避开雷区、发现好菜"。

## 功能特性

- 用户注册 / 登录 / 退出 / 密保找回密码（BCrypt 加密存储，Session 管理登录态）
- 菜品列表 + 人气榜 Top10（按平均分排序）
- 搜索（菜名 / 食堂）+ 评分筛选 + 分页
- 菜品详情：菜品信息 + 评价列表 + 平均分展示
- 提交评价：星级打分、文字点评、上传晒图（同一用户对同一菜品只能评一次）
- 平均分自动重算
- 建议箱：提交建议 / 我的建议 / 公开回复墙 / 管理员回复
- AI 助手（DeepSeek）：菜品评价一句话总结、首页对话（所有人可用）、建议箱后台助手（仅管理员）
- 管理员后台：菜品增删改、建议回复与删除
- 防刷限流：注册 / 登录 / 评价 / 建议 / AI 接口
- 自定义错误页、响应式 UI

## 技术栈

| 分类 | 技术 |
|------|------|
| 框架 | Spring Boot 3.5 |
| 持久层 | MyBatis-Plus 3.5 |
| 模板引擎 | Thymeleaf |
| 数据库 | MySQL 8 |
| 工具库 | Lombok、EasyExcel、Spring Security Crypto（仅用 BCrypt） |
| 构建 | Maven |

## 项目结构

```
src/main/java/org/example/canteenreview/
├── common/          # 通用组件（内存滑动窗口限流 RateLimiter）
├── config/          # MyBatis-Plus 插件、Web 配置（拦截器 / 图片目录映射）
├── controller/      # 页面与接口入口（菜品、用户、管理员、建议箱、AI）
├── entity/          # Dish / Review / User / Suggestion
├── interceptor/     # 登录拦截器、管理员拦截器
├── mapper/          # MyBatis-Plus BaseMapper
└── service/         # 业务接口与实现（含 FileStorageService 图片落盘）

src/main/resources/
├── templates/       # Thymeleaf 页面
├── static/css|js    # 样式与公共脚本（ai.css / ai-chat.js）
├── application.yaml # 数据库、图片目录、AI 密钥等配置
└── schema.sql       # 建表脚本（需手动执行）
```

## 本地运行

1. **建库建表**：执行 `src/main/resources/schema.sql`。注意脚本里带 `DROP TABLE`，会清空已有数据。
2. **数据库连接**：在 `application.yaml` 里改地址和账号，密码通过环境变量 `MYSQL_PASSWORD` 传入。
3. **图片目录**：修改 `app.image-dir`（默认 `D:/canteen-images/`），需与 `app.image-url-prefix` 对应。
4. **AI 功能**：设置环境变量 `DEEPSEEK_API_KEY`。没设置也能启动，AI 接口只会返回"还没配置密钥"的提示。
5. **启动**：`./mvnw spring-boot:run`（Windows 用 `mvnw.cmd`），访问 <http://localhost:8080>。

## 管理员账号

没有"注册即管理员"的入口，需要手动把账号提权：

```sql
UPDATE `user` SET role = 'ADMIN' WHERE username = '你的用户名';
```

## Excel 批量导入

目前只提供手动运行的测试类 `src/test/java/org/example/canteenreview/ExcelImportTest.java`（默认 `@Disabled`），
没有页面入口。需要导入时把里面的文件路径改成自己的 Excel，去掉 `@Disabled` 后单独运行该方法。

## 注意事项

- `schema.sql` 不会自动执行（Spring Boot 对 MySQL 默认不跑初始化脚本），表结构变更也需手动同步。
- 限流数据保存在 JVM 内存里，重启即清零；多实例部署需要改用 Redis 等共享存储。
- 部署在 Nginx 之后时，把 `app.trust-proxy` 设为 `true`，并让 Nginx 覆盖 `X-Forwarded-For`
  （`proxy_set_header X-Forwarded-For $remote_addr`），否则限流会按代理 IP 统计。
- 上线前记得把 `spring.thymeleaf.cache` 改回 `true`，并用最小权限的数据库账号代替 root。
