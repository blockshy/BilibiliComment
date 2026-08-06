#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
forbidden='请求凭据|秘密|校验|需校验|最近校验|健康度|期望状态|规范来源|全量采集|UP 主内容发现|最近新增'

if rg -n "$forbidden" \
  "$repo_root/README.md" \
  "$repo_root/frontend/README.md" \
  "$repo_root/frontend/src" \
  "$repo_root/openapi/openapi.yaml" \
  "$repo_root/src/main/java"; then
  printf '发现未统一的管理员可见术语，请按术语表修正。\n' >&2
  exit 1
fi

printf '管理员可见术语检查通过。\n'
