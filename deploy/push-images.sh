#!/usr/bin/env bash
set -euo pipefail

[[ "$#" -eq 1 ]] || {
  echo "Usage: ./deploy/push-images.sh /absolute/path/to/build.json" >&2
  exit 2
}

control_root="${TYUKKI_DEPLOY_CONTROL_ROOT:-/huyu/bootstrap/remote-deploy}"
entrypoint="${control_root}/scripts/push-container-images.sh"
[[ -x "$entrypoint" ]] || {
  echo "Missing remote deployment control entrypoint: ${entrypoint}" >&2
  exit 1
}

exec "$entrypoint" bilibili-comment "$1"
