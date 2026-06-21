#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Deployment aborted: .env is missing" >&2
  exit 1
fi

required=(
  PUBLIC_DOMAIN
  WWW_DOMAIN
  ISSUER
  ACCOUNT_URL
  API_URL
  ALLOWED_ORIGINS
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

public_domain="$(grep '^PUBLIC_DOMAIN=' .env | cut -d= -f2-)"
www_domain="$(grep '^WWW_DOMAIN=' .env | cut -d= -f2-)"
canonical_url="https://${public_domain}"

if [ "${www_domain}" != "www.${public_domain}" ]; then
  echo "Deployment aborted: WWW_DOMAIN must be www.${public_domain}" >&2
  exit 1
fi

for name in ISSUER ACCOUNT_URL API_URL ALLOWED_ORIGINS; do
  value="$(grep "^${name}=" .env | cut -d= -f2-)"
  if [ "${value}" != "${canonical_url}" ]; then
    echo "Deployment aborted: ${name} must equal ${canonical_url}" >&2
    exit 1
  fi
done

cp -f Caddyfile.production Caddyfile
docker compose up -d --build --remove-orphans
docker compose exec -T postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 1"'

api_url="$(grep '^API_URL=' .env | cut -d= -f2-)"
for _ in $(seq 1 30); do
  if curl --fail --silent "${api_url}/ready" >/dev/null; then
    echo "Deployment is ready at ${api_url}"
    exit 0
  fi
  sleep 5
done

echo "Deployment failed readiness verification" >&2
docker compose ps
docker compose logs --tail=200 backend caddy
exit 1
