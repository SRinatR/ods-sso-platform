#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

env_file="${ENV_FILE:-/etc/ods-platform/production.env}"

compose() {
  docker compose --env-file "${env_file}" "$@"
}

if ! command -v curl >/dev/null 2>&1; then
  echo "Deployment aborted: curl is missing; run scripts/server-bootstrap.sh first" >&2
  exit 1
fi

ensure_docker_forwarding() {
  if ! command -v iptables >/dev/null 2>&1; then
    echo "Deployment aborted: iptables is required for Docker port forwarding" >&2
    exit 1
  fi
  if iptables -C FORWARD -j DOCKER-FORWARD >/dev/null 2>&1; then
    return
  fi
  echo "Docker forwarding rules are missing; restarting Docker to restore published ports"
  systemctl restart docker
  for _ in $(seq 1 30); do
    if iptables -C FORWARD -j DOCKER-FORWARD >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Deployment aborted: Docker forwarding rules were not restored" >&2
  exit 1
}

ensure_docker_forwarding

if [ ! -f "${env_file}" ]; then
  echo "Deployment aborted: ${env_file} is missing" >&2
  exit 1
fi

required=(
  PUBLIC_DOMAIN
  ROOT_DOMAIN
  WWW_DOMAIN
  ACCOUNT_DOMAIN
  PARTNER_DOMAIN
  ADMIN_DOMAIN
  API_DOMAIN
  DOCS_DOMAIN
  STATUS_DOMAIN
  SSO_DOMAIN
  SCIM_DOMAIN
  WEBHOOKS_DOMAIN
  ISSUER
  ACCOUNT_URL
  PARTNERS_URL
  API_URL
  ALLOWED_ORIGINS
  ALLOWED_ORIGIN_PATTERNS
  SESSION_COOKIE_DOMAIN
  POSTGRES_PASSWORD
  SESSION_SECRET
  TOKEN_PEPPER
  TOTP_ENCRYPTION_KEY
  JWT_PRIVATE_KEY
  JWT_PUBLIC_KEY
  REQUIRE_EMAIL_VERIFICATION
  SMTP_HOST
  SMTP_USER
  SMTP_PASSWORD
  MAIL_FROM
  MINIO_ROOT_USER
  MINIO_ROOT_PASSWORD
  MINIO_BUCKET
)

for name in "${required[@]}"; do
  if ! grep -q "^${name}=.\+" "${env_file}"; then
    echo "Deployment aborted: ${name} is not configured" >&2
    exit 1
  fi
done

public_domain="$(grep '^PUBLIC_DOMAIN=' "${env_file}" | cut -d= -f2-)"
root_domain="$(grep '^ROOT_DOMAIN=' "${env_file}" | cut -d= -f2-)"
www_domain="$(grep '^WWW_DOMAIN=' "${env_file}" | cut -d= -f2-)"
canonical_url="https://${public_domain}"

if [ "${public_domain}" != "auth.${root_domain}" ]; then
  echo "Deployment aborted: PUBLIC_DOMAIN must equal auth.${root_domain}" >&2
  exit 1
fi

if [ "${www_domain}" != "www.${root_domain}" ]; then
  echo "Deployment aborted: WWW_DOMAIN must equal www.${root_domain}" >&2
  exit 1
fi

declare -A service_domains=(
  [ACCOUNT_DOMAIN]="accounts"
  [PARTNER_DOMAIN]="partners"
  [ADMIN_DOMAIN]="admin"
  [API_DOMAIN]="api"
  [DOCS_DOMAIN]="docs"
  [STATUS_DOMAIN]="status"
  [SSO_DOMAIN]="sso"
  [SCIM_DOMAIN]="scim"
  [WEBHOOKS_DOMAIN]="webhooks"
)

for name in "${!service_domains[@]}"; do
  value="$(grep "^${name}=" "${env_file}" | cut -d= -f2-)"
  expected="${service_domains[${name}]}.${root_domain}"
  if [ "${value}" != "${expected}" ]; then
    echo "Deployment aborted: ${name} must equal ${expected}" >&2
    exit 1
  fi
done

for name in ISSUER ACCOUNT_URL API_URL; do
  value="$(grep "^${name}=" "${env_file}" | cut -d= -f2-)"
  if [ "${value}" != "${canonical_url}" ]; then
    echo "Deployment aborted: ${name} must equal ${canonical_url}" >&2
    exit 1
  fi
done

partners_url="$(grep '^PARTNERS_URL=' "${env_file}" | cut -d= -f2-)"
if [ "${partners_url}" != "https://partners.${root_domain}" ]; then
  echo "Deployment aborted: PARTNERS_URL must equal https://partners.${root_domain}" >&2
  exit 1
fi

allowed_origin_patterns="$(grep '^ALLOWED_ORIGIN_PATTERNS=' "${env_file}" | cut -d= -f2-)"
session_cookie_domain="$(grep '^SESSION_COOKIE_DOMAIN=' "${env_file}" | cut -d= -f2-)"
if [ "${allowed_origin_patterns}" != "https://*.${root_domain}" ]; then
  echo "Deployment aborted: ALLOWED_ORIGIN_PATTERNS must equal https://*.${root_domain}" >&2
  exit 1
fi
if [ "${session_cookie_domain}" != "${root_domain}" ]; then
  echo "Deployment aborted: SESSION_COOKIE_DOMAIN must equal ${root_domain}" >&2
  exit 1
fi

require_email_verification="$(grep '^REQUIRE_EMAIL_VERIFICATION=' "${env_file}" | cut -d= -f2-)"
if [ "${require_email_verification}" != "true" ]; then
  echo "Deployment aborted: REQUIRE_EMAIL_VERIFICATION must be true in production" >&2
  exit 1
fi

compose config --quiet
compose up -d --build minio minio-init

backup_dir="${BACKUP_DIR:-/var/backups/ods-platform}"
if compose ps --status running --services | grep -qx postgres; then
  ENV_FILE="${env_file}" BACKUP_DIR="${backup_dir}" scripts/backup.sh
fi

compose up -d --build --remove-orphans
compose run --rm --no-deps --entrypoint caddy caddy \
  validate --config /etc/caddy/Caddyfile --adapter caddyfile
compose up -d --force-recreate --no-deps caddy
ensure_docker_forwarding
compose exec -T postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 1"'
compose exec -T postgres sh -lc \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select uuidv7()"'

uuid_table_count="$(
  compose exec -T postgres sh -lc \
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL' |
select count(*)
from information_schema.columns
where table_schema = current_schema()
  and column_name = 'internal_id'
  and udt_name = 'uuid'
  and table_name = any(array[
    'tenants',
    'users',
    'user_sessions',
    'account_tokens',
    'mfa_methods',
    'backup_codes',
    'login_history',
    'audit_logs',
    'user_consents',
    'security_policies',
    'trusted_devices',
    'risk_assessments',
    'domain_outbox',
    'used_refresh_tokens',
    'federation_providers',
    'key_metadata',
    'partner_organizations',
    'partner_memberships',
    'partner_applications'
  ]);
SQL
  tr -d '[:space:]'
)"

if [ "${uuid_table_count}" != "19" ]; then
  echo "Deployment failed UUIDv7 schema verification: expected 19 UUID-backed tables, found ${uuid_table_count}" >&2
  exit 1
fi

install -m 0644 ops/systemd/ods-platform.service /etc/systemd/system/ods-platform.service
install -m 0644 ops/systemd/ods-platform-backup.service /etc/systemd/system/ods-platform-backup.service
install -m 0644 ops/systemd/ods-platform-backup.timer /etc/systemd/system/ods-platform-backup.timer
chmod 0755 scripts/deploy.sh scripts/backup.sh scripts/restore.sh scripts/boot-recover.sh
systemctl daemon-reload
systemctl enable ods-platform.service ods-platform-backup.timer
systemctl start ods-platform-backup.timer

api_url="$(grep '^API_URL=' "${env_file}" | cut -d= -f2-)"
api_host="${api_url#*://}"
api_host="${api_host%%/*}"
api_host="${api_host%%:*}"

# A fresh host can need several minutes to issue the initial certificate set.
# Verify the real Caddy TLS route locally so deploy health does not depend on
# public-IP hairpin routing while still validating SNI and the certificate.
for _ in $(seq 1 180); do
  if curl \
    --fail \
    --silent \
    --max-time 10 \
    --resolve "${api_host}:443:127.0.0.1" \
    "${api_url}/ready" >/dev/null; then
    systemctl start ods-platform.service
    systemctl is-active --quiet ods-platform.service
    echo "Deployment is ready at ${api_url}"
    exit 0
  fi
  sleep 5
done

echo "Deployment failed readiness verification" >&2
compose ps
compose logs --tail=200 backend caddy
exit 1
