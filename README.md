# 吃啥饭 · 校园食堂评价系统 🍚

一个基于 Spring Boot 的校园食堂菜品评价 Web 应用。同学可以浏览食堂菜品、查看人气榜、给菜品打分并晒图评价，帮助大家"避开雷区、发现好菜"。

## 功能特性

**基础功能**

- 用户注册 / 登录 / 退出 / 密保找回密码（BCrypt 加密存储，Session 管理登录态）
- 菜品列表 + 人气榜 Top10（按平均分排序）
- 搜索（菜名 / 食堂）+ 评分筛选 + 分页
- 菜品详情：菜品信息 + 评价列表 + 平均分展示
- 提交评价：星级打分、文字点评、上传晒图（同一用户对同一菜品只能评一次）
- 平均分自动重算
- 建议箱：提交建议 / 我的建议 / 公开回复墙 / 管理员回复
- AI 助手（DeepSeek）：菜品评价一句话总结、首页对话（所有人可用）、建议箱后台助手（仅管理员）
- 管理员后台：菜品增删改、评价审核、建议回复与删除
- 自定义错误页、响应式 UI

**安全与治理**

- 密码、密保答案均 BCrypt 存储，绝不落明文
- CSRF 双重防护：`CsrfInterceptor` 校验 token（常量时间比较）+ `OriginCheckInterceptor` 校验来源
- 登录失败锁定（连续失败即临时锁定），注册 / 登录 / 评价 / 建议 / AI 接口全局限流
- 安全响应头：HSTS（仅 HTTPS 下发）、X-Content-Type-Options、Referrer-Policy 等
- 晒图四道校验：扩展名白名单 + 文件头魔数 + 二维码检测（挡收款码/加群码）+ 文件名重写（防路径穿越）
- 内容治理：敏感词过滤（含拼音变体，挡 "jiaweixin" 这类绕过）+ 违规累计封禁 + 评论先审后发
- AI 调用配额，防止接口被刷
- 限流与封禁状态默认存内存，可通过 `app.rate-limit.store=redis` 切到 Redis 多实例共享

## 技术栈

| 分类 | 技术 |
|------|------|
| 框架 | Spring Boot 4.1.2（Java 17 / Tomcat 11 / Jackson 3） |
| 持久层 | MyBatis-Plus 3.5.18.1 + MySQL 8（mysql-connector-j 26.10.0） |
| 模板引擎 | Thymeleaf |
| 缓存/状态 | Redis（可选，多实例部署时启用） |
| 工具库 | Lombok、EasyExcel 4.0.3（POI 5.5.1）、ZXing 3.5.3、pinyin4j、Spring Security Crypto（仅用 BCrypt） |
| 构建 | Maven Wrapper（`mvnw` / `mvnw.cmd`） |
| 质量 | OWASP dependency-check 12.1.3（`security-scan` profile，默认不激活） |

## 项目结构

```
src/main/java/org/example/canteenreview/
├── common/          # 通用组件：限流、敏感词、违规守卫、安全响应头、AI 配额、全局异常
│   ├── ratelimit/   # 限流存储抽象（内存 / Redis）
│   └── violation/   # 违规封禁状态存储（内存 / Redis）
├── config/          # MyBatis-Plus 插件、Web 配置（拦截器注册 / 图片目录映射）
├── controller/      # 页面与接口入口（菜品、用户、管理员、建议箱、AI）
├── entity/          # Dish / Review / ReviewAuditLog / Suggestion / User
├── interceptor/     # 登录、管理员、CSRF、Origin 校验
├── mapper/          # MyBatis-Plus BaseMapper
└── service/         # 业务接口与实现（含 FileStorageService 图片落盘）

src/main/resources/
├── templates/          # Thymeleaf 页面
├── static/css|js       # 样式与公共脚本
├── application.yaml    # 开发环境配置（数据库、图片目录、AI 密钥等）
├── application-prod.yaml # 生产环境覆盖（TLS、模板缓存、目录、反向代理头）
├── schema.sql          # 建表脚本（需手动执行）
├── sensitive-words.txt # 敏感词库（支持外部路径热更新）
└── logback-spring.xml  # 日志配置（按天滚动）

deploy/
├── canteen-review.service # systemd unit（已做安全加固）
└── env.example            # 生产环境变量模板
```

## 本地运行

1. **建库建表**：执行 `src/main/resources/schema.sql`。注意脚本里带 `DROP TABLE`，会清空已有数据。
2. **数据库连接**：在 `application.yaml` 里改地址和账号，密码通过环境变量 `MYSQL_PASSWORD` 传入。
3. **图片目录**：修改 `app.image-dir`（默认 `D:/canteen-images/`），需与 `app.image-url-prefix` 对应。
4. **AI 功能**：设置环境变量 `DEEPSEEK_API_KEY`。没设置也能启动，AI 接口只会返回"还没配置密钥"的提示。
5. **启动**：`./mvnw spring-boot:run`（Windows 用 `mvnw.cmd`），访问 <http://localhost:8080>。

本地默认不激活任何 profile，`application-prod.yaml` 里的生产覆盖不会生效。

## 生产部署

### 1. 打包

```bash
./mvnw -DskipTests package
# 产物：target/CanteenReview-0.0.1-SNAPSHOT.jar
```

### 2. 环境变量

把 `deploy/env.example` 复制到服务器 `/etc/canteen-review/env`，改掉密码与 Key 后收紧权限：

```bash
chown root:root /etc/canteen-review/env
chmod 600 /etc/canteen-review/env
```

**`SPRING_PROFILES_ACTIVE=prod` 这一行不能漏**。漏了不会有任何报错，但数据库 TLS、模板缓存、图片目录等生产覆盖会静默失效。

### 3. 数据库账号

不要用 root，用最小权限账号并强制 SSL：

```sql
CREATE USER 'canteen'@'localhost' IDENTIFIED BY '强密码' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE ON canteen_review.* TO 'canteen'@'localhost';
FLUSH PRIVILEGES;
```

`schema.sql` 不会自动执行（Spring Boot 对 MySQL 默认不跑初始化脚本），建表请手动执行，因此应用账号不需要 DDL 权限。

### 4. systemd

```bash
mkdir -p /opt/canteen-review/logs && chown canteen:canteen /opt/canteen-review/logs
cp deploy/canteen-review.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now canteen-review
```

图片目录由 unit 里的 `StateDirectory=canteen-review` 自动创建并授权，无需手工建目录。

### 5. Nginx（反代 + HTTPS）

```nginx
client_max_body_size 10m;   # 与 spring.servlet.multipart 对齐，否则大图 413

proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header Host              $host;

add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

`application-prod.yaml` 里已开启 `server.forward-headers-strategy: native`，与上面的头配合才能让 HSTS 下发、重定向生成 https 地址。**只有确认 Nginx 会覆盖这些头时才保留该配置**；服务端口直接暴露公网时打开它，等于让客户端自己伪造协议。

### 6. 验证

```bash
journalctl -u canteen-review -n 30 | grep "profile is active"
```

必须看到 `The following 1 profile is active: "prod"`。

## 依赖安全扫描

```bash
./mvnw -Psecurity-scan dependency-check:check
```

- 首次运行会下载 NVD 漏洞库（几百 MB），本地已有库时加 `-DautoUpdate=false` 跳过更新
- 建议配置环境变量 `NVD_API_KEY`（免费申请），否则全量更新非常慢；配置里读取的就是这个变量名，不会把密钥写进仓库
- CVSS ≥ 7 会让命令失败，适合挂在 CI 做发布前卡口
- 报告输出到 `target/dependency-check-report.html` 与 `.json`

## 管理员账号

没有"注册即管理员"的入口，需要手动把账号提权：

```sql
UPDATE `user` SET role = 'ADMIN' WHERE username = '你的用户名';
```

## Excel 批量导入

目前只提供手动运行的测试类 `src/test/java/org/example/canteenreview/ExcelImportTest.java`（默认 `@Disabled`），
没有页面入口。需要导入时把里面的文件路径改成自己的 Excel，去掉 `@Disabled` 后单独运行该方法。

## 注意事项

- **新增接口默认是公开的**：认证采用拦截器白名单模式，`LoginInterceptor` 只拦 `/dish/review` 和 `/admin/**`，`AdminInterceptor` 只拦 `/admin/**`。新增需要登录或管理员权限的接口时，必须同步在 `WebConfig` 里补上拦截路径，否则忘了配就是对外公开。
- **多实例必须切 Redis**：`app.rate-limit.store` 默认 `memory`，每台机器各记各的限流和封禁状态，换台机器就能绕过。注意切 `redis` 前先确认 Redis 可用，否则请求会报错。
- **反代部署**：`app.trust-proxy` 保持 `false`，直连时打开会让客户端伪造 `X-Forwarded-For` 绕过限流；前面挂 Nginx 时应让 Nginx 覆盖该头。
- **表结构变更需手动同步**，`schema.sql` 不会自动跑。
- **图片目录**需要应用用户可写，生产环境由 systemd 的 `StateDirectory` 提供。
