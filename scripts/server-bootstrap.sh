#!/usr/bin/env bash
# One-time server bootstrap (run as root on VPS)
set -euo pipefail

DEPLOY_PATH="${1:-/opt/ods-platform}"

echo "==> Installing Docker..."
if ! command -v docker &>/dev/null; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker
  systemctl start docker
fi

echo "==> Creating deploy directory: $DEPLOY_PATH"
mkdir -p "$DEPLOY_PATH"

if [ ! -f "$DEPLOY_PATH/.env" ]; then
  echo "==> Creating .env from staging example — EDIT SECRETS NOW"
  cp "$DEPLOY_PATH/.env.staging.example" "$DEPLOY_PATH/.env" 2>/dev/null || true
  echo ""
  echo "IMPORTANT: edit $DEPLOY_PATH/.env before first deploy:"
  echo "  POSTGRES_PASSWORD, KEYCLOAK_ADMIN_PASSWORD, SESSION_SECRET,"
  echo "  TATARLAR_CLIENT_SECRET, JWT keys"
fi

echo "==> Opening firewall ports 80, 443 (ufw if present)..."
if command -v ufw &>/dev/null; then
  ufw allow 80/tcp || true
  ufw allow 443/tcp || true
fi

echo "==> Done. Add GitHub secrets and push to main to deploy."
echo "    SSH_HOST, SSH_USER, SSH_PRIVATE_KEY, DEPLOY_PATH=$DEPLOY_PATH"
