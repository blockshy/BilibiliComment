# BilibiliComment

Bilibili 评论任务管理服务。MVP 提供单管理员登录、视频/动态评论采集、UP 主内容监控、持续增量采集或单次历史补采任务、执行历史、评论分页、SSE 实时进度和凭据脱敏管理。

## 技术架构

- API：Java 25、Spring Boot 4.1、Spring Security、MyBatis 4
- 数据库：PostgreSQL 17、Flyway；任务元数据位于 `app` schema，每个评论任务使用 `comment_data.comment_task_<id>` 独立表
- Web：React 19、TypeScript、Vite、TanStack Query
- 交付：非 root 的 API/Web 多阶段 Docker 镜像

后端按 `interfaces → application → domain ← infrastructure` 分层。`openapi/openapi.yaml` 是浏览器 API 的契约源，生成的 TypeScript 类型不得手工修改。

## 本地验证

宿主机不需要 Java 或 Maven，但需要 Docker。后端测试会启动隔离的 PostgreSQL 17 Testcontainers，不会连接共享数据库或真实 Bilibili：

```bash
./scripts/test-backend.sh
```

前端要求 Node.js 22：

```bash
cd frontend
npm ci
npm run api:check
npm run lint
npm test
npm run test:e2e
npm run build
npm run dev:mock
```

`dev:mock` 仅在 Vite 开发模式使用浏览器内 typed fixture。Playwright 会主动阻断 `/api` 和站外请求；普通开发和生产构建始终请求同源 `/api/v1`。

构建容器镜像：

```bash
docker build -f docker/api.Dockerfile -t bilibili-comment-api:local .
docker build -f docker/web.Dockerfile -t bilibili-comment-web:local .
```

## 配置与迁移

公开配置示例见 `.env.example`；数据库密码、管理员 BCrypt 哈希和 32 字节 Base64 加密密钥必须保持空白，真实值只放入被忽略的私密环境文件。常驻 API 使用 `*_app` 账号并设置 `FLYWAY_ENABLED=false`；一次性迁移进程使用独立 `*_migrator` 账号运行：

```bash
APP_MODE=migrate java -jar application.jar
```

迁移入口会强制启用 Flyway，避免复用常驻 API 的 `FLYWAY_ENABLED=false` 时静默跳过迁移。迁移按 `V1`–`V10` 从空库执行。运行账号无建表权限，只能操作任务、执行及发现关系元数据，并通过受控 `SECURITY DEFINER` 函数写入、检索任务评论。

Dev/Prod 启动会验证环境名、固定域名、数据库名、运行账号、Secure Cookie 和关闭常驻 Flyway，防止环境误连。上游瞬时网络、限流和 5xx 错误会先执行有限请求重试；用尽后进入持久化 `RETRY_WAIT`，按配置退避并可在重启后恢复。

## 安全边界

禁止提交或记录数据库密码、管理员哈希、加密密钥及 Bilibili Cookie。自动化测试禁止访问真实上游、共享 PostgreSQL 和线上域名。当前授权仅覆盖开发与本地 Dev 验证；数据库开通、DNS/TLS/Nginx、Dev 部署和任何 Prod 操作均需额外明确指令。
