# API 契约

`openapi.yaml` 是 `/api/v1` 的版本化契约源。修改后端 DTO 或路径时，应先同步此文件，再生成并核对前端类型：

```bash
npx openapi-typescript openapi/openapi.yaml -o frontend/src/api/generated.d.ts
```

SSE 的线协议以 `text/event-stream` 表示，单条 `data` 的 JSON 结构使用 `LiveEvent` schema。生成文件不得手工修改，也不得把 Cookie、密码或 Bilibili 凭据写入示例。
