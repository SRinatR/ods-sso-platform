# Staging deployment

## Server preparation

```bash
mkdir -p /opt/ods-platform
cp .env.staging.example /opt/ods-platform/.env
chmod 600 /opt/ods-platform/.env
```

Generate independent secrets. Do not print, commit or send them through ordinary chat:

```bash
openssl rand -base64 48
openssl rand -base64 32 | tr '+/' '-_' | tr -d '='
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072
```

Required configuration:

- database password
- `SESSION_SECRET`
- `TOKEN_PEPPER`
- `TOTP_ENCRYPTION_KEY`
- RS256 key pair
- SMTP settings
- bootstrap admin only for the first controlled startup
- OAuth client secrets

Run:

```bash
cp Caddyfile.staging Caddyfile
docker compose up -d --build
docker compose exec -T backend alembic current
curl --fail https://staging.api.ods.uz/ready
```

The backend image applies migrations before starting Uvicorn. A failed migration prevents service startup.

