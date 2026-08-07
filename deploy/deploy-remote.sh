#!/usr/bin/env bash
set -euo pipefail

[[ "$#" -eq 3 ]] || {
  echo "Usage: ./deploy/deploy-remote.sh /absolute/path/to/release.json dev <full-commit>" >&2
  exit 2
}
[[ "$2" == "dev" ]] || {
  echo "BilibiliComment supports remote Dev deployment only." >&2
  exit 2
}

control_root="${TYUKKI_DEPLOY_CONTROL_ROOT:-/huyu/bootstrap/remote-deploy}"
entrypoint="${control_root}/scripts/deploy-bilibili-dev.sh"
[[ -x "$entrypoint" ]] || {
  echo "Missing remote deployment control entrypoint: ${entrypoint}" >&2
  exit 1
}

exec "$entrypoint" "$1" "$3"
