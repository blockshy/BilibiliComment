# BilibiliComment Dev 发布入口

本目录只提供 BilibiliComment 的三阶段发布入口。全量源码门禁只在本机构建阶段运行；
镜像推送和远端 Dev 部署不会重新执行 npm、Maven、Playwright 或 Docker build。

BilibiliComment 只允许部署到 Dev，不存在 Prod 入口。公共安全实现、Ansible inventory 和
manifest schema 位于 `${TYUKKI_DEPLOY_CONTROL_ROOT:-/huyu/bootstrap/remote-deploy}`。

## 脚本一览

| 脚本 | 用途 | 是否连接远端 |
| --- | --- | --- |
| `start-local-test.sh` | 启动浏览器内 mock 前端，供日常开发检查 | 否 |
| `build-images.sh` | 运行完整门禁并构建本地 API/Web 镜像 | 否 |
| `push-images.sh` | 推送已经验证的镜像并生成 `release.json` | 仅 GHCR |
| `deploy-remote.sh` | 按 digest 部署远端 Dev | 是 |

## 0. 日常本地启动

```bash
cd /huyu/workspace/WebSite/BilibiliComment
./deploy/start-local-test.sh
```

脚本默认先执行 `npm ci`，随后在 `http://127.0.0.1:5173` 启动浏览器 mock。它不会访问
真实 API、PostgreSQL 或 Bilibili，也不读取应用秘密。依赖刚安装且 lockfile 未变化时可快速
重启：

```bash
./deploy/start-local-test.sh --skip-install
```

服务以前台进程运行，按 `Ctrl-C` 停止；因此不再需要 `stop-dev.sh` 一类脚本。

## 1. 本地测试并构建镜像

```bash
cd /huyu/workspace/WebSite/BilibiliComment
./deploy/build-images.sh
```

入口要求当前分支 clean 且与 upstream 完全一致，并运行后端全量测试、生产依赖审计、
OpenAPI 检查、lint、前端单元测试、Playwright E2E、生产构建及 API/Web 镜像构建。
成功后只生成本地镜像和：

```text
/huyu/artifacts/remote-deploy/containers/bilibili-comment/<full-commit>/build.json
```

此阶段不读取 GHCR 凭据、不推送镜像、不连接远端服务器。

## 2. 推送不可变镜像

```bash
./deploy/push-images.sh \
  /huyu/artifacts/remote-deploy/containers/bilibili-comment/<full-commit>/build.json
```

上传阶段验证 `build.json`、本地 image ID、OCI revision/source 和完整 commit tag，随后把
API/Web 镜像推送到 GHCR 并生成同目录的 `release.json`。它不会读取源码或重新运行测试。
已有不可变 tag 不会被覆盖。

GHCR 凭据只能通过 mode `0600` 的 `~/.docker/config.json` 或调用者设置的
`DOCKER_CONFIG` 提供，禁止把 PAT 写入脚本、README、参数或项目 `.env`。

## 3. 部署远端 Dev

```bash
./deploy/deploy-remote.sh \
  /huyu/artifacts/remote-deploy/containers/bilibili-comment/<full-commit>/release.json \
  dev \
  <full-commit>
```

部署只消费最终 manifest 和 registry digest，不要求本地仓库存在。它仍会执行必要的目标
安全步骤：远端 registry 元数据检查、精确 digest 拉取、迁移前数据库备份、Flyway、权限
检查、候选健康、公开 smoke 和晋级。完整的全服务器审计不再是每次发布的前置条件。

SSH 私钥使用既有 SSH agent/`~/.ssh` 配置；数据库和应用秘密只从远端 mode `0600` 环境
文件读取，三个脚本均不会接收或输出这些值。

## 完整 Dev 发布示例

以下示例展示三个阶段如何衔接，不包含任何秘密：

```bash
cd /huyu/workspace/WebSite/BilibiliComment
commit="$(git rev-parse HEAD)"

./deploy/build-images.sh
build_manifest="/huyu/artifacts/remote-deploy/containers/bilibili-comment/${commit}/build.json"

./deploy/push-images.sh "$build_manifest"
release_manifest="${build_manifest%/build.json}/release.json"

./deploy/deploy-remote.sh "$release_manifest" dev "$commit"
```

若构建阶段提示工作树不干净或与 upstream 不一致，应先审阅并提交源码；不得通过修改脚本
跳过该保护。第二、三阶段不依赖源码测试缓存；若镜像推送结果不确定，入口会 fail closed，
应先核对 registry 状态，不能覆盖既有不可变 tag。
