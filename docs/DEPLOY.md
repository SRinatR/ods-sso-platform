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

## DNS for ods.uz

The production deployment keeps one canonical security origin and OIDC issuer:

```text
https://auth.ods.uz
```

Required records:

| Type | Name | Value |
|---|---|---|
| A | `@` | `94.232.44.189` |
| A or CNAME | `www` | `94.232.44.189` or `ods.uz` |
| A | `auth` | `94.232.44.189` |
| A | `accounts` | `94.232.44.189` |
| A | `admin` | `94.232.44.189` |
| A | `api` | `94.232.44.189` |
| A | `docs` | `94.232.44.189` |
| A | `status` | `94.232.44.189` |
| A | `sso` | `94.232.44.189` |
| A | `scim` | `94.232.44.189` |
| A | `webhooks` | `94.232.44.189` |

For the pilot, `ods.uz` and `www.ods.uz` redirect to the identity portal. The canonical OIDC issuer
remains `auth.ods.uz`, so the public website can be separated later without changing partner
configuration.

The service domains expose only implemented behavior:

- `accounts.ods.uz` redirects to the account dashboard.
- `admin.ods.uz` redirects to the administration UI.
- `docs.ods.uz` redirects to the OpenAPI UI.
- `sso.ods.uz` redirects to the canonical issuer.
- `api.ods.uz` exposes REST, health and OpenAPI routes, but not alternate OIDC issuer endpoints.
- `status.ods.uz` exposes the real database-and-Redis readiness result.
- `scim.ods.uz` and `webhooks.ods.uz` terminate TLS and return HTTP 404 until those capabilities
  are implemented. They must not claim successful service availability.

Caddy obtains and renews TLS certificates automatically. Do not install Certbot or a second ACME
renewal mechanism on the same listener. Caddy may manage separate certificates per site, avoiding
one shared private key across every subdomain.

TCP ports 80 and 443 and UDP port 443 must be reachable from the Internet. UDP 443 enables HTTP/3.
Mail delivery is a separate increment: when SMTP is connected, publish the MX/SPF/DKIM/DMARC
records provided by the selected mail service before enabling mandatory email verification.

## Deploy

```bash
bash scripts/server-bootstrap.sh /opt/ods-platform
cd /opt/ods-platform
bash scripts/deploy.sh
```

The deployment script builds the containers, confirms the newest Flyway migration and polls
`/ready`. Before changing containers it writes a mode-0600-compatible compressed PostgreSQL dump
to `BACKUP_DIR` (default `/var/backups/ods-platform`). After Flyway it calls PostgreSQL `uuidv7()`
and verifies that all 19 domain tables have native UUID internal identifiers. A failed backup,
migration or schema assertion stops the deployment.

The backend image is built in three stages. The middle stage starts the Spring context with lazy
database initialization and writes a CDS archive using the same Java 26, heap and ZGC flags as the
runtime stage. A missing or incompatible archive fails the image build instead of silently
disabling CDS.
