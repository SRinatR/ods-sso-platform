#!/usr/bin/env bash
set -euo pipefail

deploy_path="${1:-/opt/ods-platform}"

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
fi

mkdir -p "$deploy_path"
if [ ! -f "$deploy_path/.env" ]; then
  cp "$deploy_path/.env.staging.example" "$deploy_path/.env"
  chmod 600 "$deploy_path/.env"
  echo "Populate $deploy_path/.env before deployment"
fi

if command -v ufw >/dev/null 2>&1; then
  ufw allow 80/tcp
  ufw allow 443/tcp
fi

echo "Server bootstrap completed"

