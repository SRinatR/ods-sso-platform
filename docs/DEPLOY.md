# Pilot deployment

## Runtime profile

The default pilot profile runs:

- PostgreSQL
- Redis
- Kotlin/Spring backend
- Next.js account and partner portal
- Caddy with automatic TLS

Kafka is enabled with `docker compose --profile events ...`. Prometheus, Grafana and the
OpenTelemetry Collector are enabled with `--profile observability`. They are disabled on the
initial 2 GB VPS.

## Required configuration

Copy `.env.production.example` to `.env` on the server and configure:

- public domain and ACME email
- PostgreSQL password
- independent `SESSION_SECRET` and `TOKEN_PEPPER`
- 32-byte URL-safe base64 `TOTP_ENCRYPTION_KEY`
- RSA private/public signing key pair
- bootstrap administrator credentials

For the pilot, `REQUIRE_EMAIL_VERIFICATION=false` permits registration before SMTP is connected.
Set it to `true` as soon as SMTP is configured.

## Deploy

```bash
bash scripts/server-bootstrap.sh /opt/ods-platform
cd /opt/ods-platform
bash scripts/deploy.sh
```

The deployment script builds the containers, confirms the newest Flyway migration and polls
`/ready`. A failed migration prevents backend readiness and fails the deployment.
