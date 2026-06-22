#!/usr/bin/env bash
set -euo pipefail

deploy_path="${1:-/opt/ods-platform}"

if ! command -v curl >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --reinstall --no-install-recommends ca-certificates curl
fi

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
fi

if ! command -v git >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends git
fi

if [ "$(free -m | awk '/^Mem:/{print $2}')" -lt 3000 ] && [ "$(swapon --show | wc -l)" -eq 0 ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p "$deploy_path"
if [ ! -f "$deploy_path/.env" ]; then
  cp "$deploy_path/.env.production.example" "$deploy_path/.env"
  chmod 600 "$deploy_path/.env"
  echo "Populate $deploy_path/.env before deployment"
fi

if command -v ufw >/dev/null 2>&1; then
  ufw allow 80/tcp
  ufw allow 443/tcp
fi

echo "Server bootstrap completed"
