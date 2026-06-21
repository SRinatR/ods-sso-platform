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

## DNS for sso.ods.uz

The production deployment uses one canonical origin for the identity site, account portal, API and
OIDC issuer:

```text
https://sso.ods.uz
```

Required records:

| Type | Name | Value |
|---|---|---|
| A | `sso` | `94.232.44.189` |

The existing `ods.uz` and `www.ods.uz` website remains independent. Caddy obtains the
`sso.ods.uz` TLS certificate automatically. Ports 80 and 443 must be reachable from the Internet.

No separate `api` or `auth` DNS records are required for the pilot. Mail delivery is a separate
increment: when SMTP is connected, publish the MX/SPF/DKIM/DMARC records provided by the selected
mail service before enabling mandatory email verification.

## Deploy

```bash
bash scripts/server-bootstrap.sh /opt/ods-platform
cd /opt/ods-platform
bash scripts/deploy.sh
```

The deployment script builds the containers, confirms the newest Flyway migration and polls
`/ready`. A failed migration prevents backend readiness and fails the deployment.
