# Bilibili 评论任务前端

React + TypeScript + Vite 管理端，所有真实请求固定使用同源 `/api/v1`。`openapi/openapi.yaml` 是 HTTP/SSE 传输契约的唯一来源，生成文件禁止手工编辑。

## 本地命令

```bash
npm ci
npm run api:generate # 从 OpenAPI 重新生成 src/api/generated.d.ts
npm run api:check    # 验证已生成类型与 OpenAPI 一致
npm run dev          # 连接本地真实 API
npm run dev:mock     # 显式启用内存模拟数据
npm run lint
npm test
npm run build
npm run test:e2e    # 启动 mock 开发服务器并运行 Chromium 验收
```

模拟数据仅在 Vite 开发模式且 `VITE_ENABLE_MOCKS=true` 时启用；管理员会话及任务状态只保存在当前标签页的 `sessionStorage`，用于验证刷新恢复。生产构建始终使用真实 API，不会自动降级到模拟数据。浏览器环境变量只允许包含环境名和公开版本号，禁止放入 Cookie、管理员密码或数据库配置。

首次运行端到端测试前执行 `npm run test:e2e:install` 安装与锁定版本匹配的 Chromium。验收会阻断 `/api` 及站外请求，只测试浏览器内的模拟 API，不访问真实后端或 Bilibili。

测试边界：Vitest 验证组件与 HTTP 适配逻辑，Playwright 使用实现 `ApiService` 的类型化内存 mock 验证管理员工作流，并在浏览器层阻断真实网络。该层不替代后端 PostgreSQL、鉴权/CSRF、OpenAPI 契约或容器集成测试。
