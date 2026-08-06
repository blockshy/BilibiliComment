#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docker_socket="${DOCKER_SOCKET:-/var/run/docker.sock}"
maven_cache="${MAVEN_CACHE_DIR:-${XDG_CACHE_HOME:-${HOME}/.cache}/bilibili-comment/maven}"

if [[ ! -S "${docker_socket}" ]]; then
  echo "Docker socket not found: ${docker_socket}" >&2
  exit 1
fi

mkdir -p "${maven_cache}"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --group-add "$(stat -c %g "${docker_socket}")" \
  --network host \
  -e HOME=/tmp \
  -e MAVEN_CONFIG=/m2 \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  -v "${docker_socket}:/var/run/docker.sock" \
  -v "${maven_cache}:/m2" \
  -v "${repo_root}:/workspace" \
  -w /workspace \
  maven:3.9-eclipse-temurin-25 \
  mvn -B -ntp -Dmaven.repo.local=/m2/repository "$@" test
