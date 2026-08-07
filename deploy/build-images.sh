#!/usr/bin/env bash
set -euo pipefail

[[ "$#" -eq 0 ]] || {
  echo "Usage: ./deploy/build-images.sh" >&2
  exit 2
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
control_root="${TYUKKI_DEPLOY_CONTROL_ROOT:-/huyu/bootstrap/remote-deploy}"
entrypoint="${control_root}/scripts/build-bilibili-images.sh"
[[ -x "$entrypoint" ]] || {
  echo "Missing remote deployment control entrypoint: ${entrypoint}" >&2
  exit 1
}

exec env BILIBILI_REPO_ROOT="$repo_root" "$entrypoint"
