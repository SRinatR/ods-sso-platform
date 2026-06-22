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
for _ in $(seq 1 60); do
  if curl --fail --silent "${api_url}/ready" >/dev/null; then
    docker compose --env-file "${env_file}" up -d --force-recreate --no-deps caddy
    echo "ODS platform recovered at ${api_url}"
    exit 0
  fi
  sleep 5
done

docker compose --env-file "${env_file}" ps
echo "ODS platform recovery failed readiness verification" >&2
exit 1
