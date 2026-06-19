#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Deployment aborted: .env is missing" >&2
  exit 1
fi

required=(
  POSTGRES_PASSWORD
  SESSION_SECRET
  TOKEN_PEPPER
  TOTP_ENCRYPTION_KEY
  JWT_PRIVATE_KEY
  JWT_PUBLIC_KEY
)

for name in "${required[@]}"; do
  if ! grep -q "^${name}=.\+" .env; then
    echo "Deployment aborted: ${name} is not configured" >&2
    exit 1
  fi
done

cp -f Caddyfile.staging Caddyfile
docker compose up -d --build --remove-orphans
docker compose exec -T backend alembic current
curl --fail --silent https://staging.api.ods.uz/ready >/dev/null
echo "Staging deployment is ready"

