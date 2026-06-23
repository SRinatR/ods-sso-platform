#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
env_file="${ENV_FILE:-/etc/ods-platform/production.env}"

if [ ! -r "${env_file}" ]; then
  echo "Recovery aborted: ${env_file} is not readable" >&2
  exit 1
fi

if ! iptables -C FORWARD -j DOCKER-FORWARD >/dev/null 2>&1; then
  systemctl restart docker
fi

docker compose --env-file "${env_file}" up -d --remove-orphans

api_url="$(grep -m1 '^API_URL=' "${env_file}" | cut -d= -f2-)"
api_host="${api_url#*://}"
api_host="${api_host%%/*}"
api_host="${api_host%%:*}"

for _ in $(seq 1 180); do
  if curl \
    --fail \
    --silent \
    --max-time 10 \
    --resolve "${api_host}:443:127.0.0.1" \
    "${api_url}/ready" >/dev/null; then
    echo "ODS platform recovered at ${api_url}"
    exit 0
  fi
  sleep 5
done

docker compose --env-file "${env_file}" ps
echo "ODS platform recovery failed readiness verification" >&2
exit 1
