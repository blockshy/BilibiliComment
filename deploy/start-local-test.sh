#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./deploy/start-local-test.sh [--skip-install] [-- <vite arguments>]

Starts the safe browser-mock frontend on http://127.0.0.1:5173.
Dependencies are refreshed with npm ci unless --skip-install is supplied.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

skip_install=0
if [[ "${1:-}" == "--skip-install" ]]; then
  skip_install=1
  shift
fi
if [[ "${1:-}" == "--" ]]; then
  shift
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
environment_file="${HOME}/.config/dev-bootstrap/environment.sh"
[[ -r "$environment_file" ]] || {
  echo "Missing managed Node environment: $environment_file" >&2
  exit 1
}
# shellcheck disable=SC1090
source "$environment_file"
command -v nvm >/dev/null 2>&1 || {
  echo "nvm is unavailable after loading $environment_file" >&2
  exit 1
}
nvm use 24.19.0 >/dev/null

cd "$repo_root/frontend"
if ((skip_install == 0)); then
  npm ci
fi

echo "Starting BilibiliComment browser-mock UI at http://127.0.0.1:5173"
echo "Press Ctrl-C to stop. No real API, database, Bilibili endpoint or secret is used."
exec npm run dev:mock -- "$@"
