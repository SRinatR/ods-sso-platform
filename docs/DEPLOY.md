# Pilot deployment

## Runtime profile

The default pilot profile runs:

- PostgreSQL
- Redis
- Kotlin/Spring backend
- Next.js account and partner portal
- Caddy with automatic TLS
- MinIO-compatible object storage for backup artifacts

Kafka is enabled with `docker compose --profile events ...`. Prometheus, Grafana and the
OpenTelemetry Collector are enabled with `--profile observability`. They are disabled on the
initial 2 GB VPS.

## Required configuration

Configure the GitHub Environment `production` as described in
[`production-deployment.md`](production-deployment.md). GitHub Actions creates the restricted
runtime file on the VPS; do not maintain a second hand-written production `.env`.

- public domain and ACME email
- PostgreSQL password
- independent `SESSION_SECRET` and `TOKEN_PEPPER`
- 32-byte URL-safe base64 `TOTP_ENCRYPTION_KEY`
- RSA private/public signing key pair
- bootstrap administrator credentials
- wildcard tenant DNS (`*.ods.uz`)
- Resend SMTP API key

Production registration requires verified email. For Resend use `smtp.resend.com:587`, username
`resend`, the Resend API key as `SMTP_PASSWORD`, and a verified ODS sender in `MAIL_FROM`.

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
| A | `partners` | `94.232.44.189` |
| A | `admin` | `94.232.44.189` |
| A | `api` | `94.232.44.189` |
| A | `docs` | `94.232.44.189` |
| A | `status` | `94.232.44.189` |
| A | `sso` | `94.232.44.189` |
| A | `scim` | `94.232.44.189` |
| A | `webhooks` | `94.232.44.189` |
| A | `*` | `94.232.44.189` |

`ods.uz` is the public product website. It does not redirect to the identity portal. The canonical
OIDC issuer remains `auth.ods.uz`.

The service domains expose only implemented behavior:

- `auth.ods.uz` owns registration, login, consent, verification and password reset.
- `accounts.ods.uz` serves only the ordinary personal account dashboard.
- `partners.ods.uz` serves only the central partner entry point: organization list and
  organization creation. It does not contain organization-specific settings.
- `admin.ods.uz` serves only the system administration UI.
- `docs.ods.uz` serves the OpenAPI UI.
- `sso.ods.uz` redirects to the canonical issuer.
- `api.ods.uz` redirects its root to `docs.ods.uz` and exposes REST, health and OpenAPI routes,
  but not alternate OIDC issuer endpoints.
- `status.ods.uz` exposes the real database-and-Redis readiness result.
- `{slug}.ods.uz` serves the matching counterparty workspace. Caddy asks the backend whether the
  slug belongs to an active organization before obtaining a certificate. Organization owners
  configure callback and post-logout URLs, client type, scopes, token endpoint authentication,
  application status and secret rotation from this isolated workspace. PKCE S256 is mandatory.
- `scim.ods.uz` and `webhooks.ods.uz` terminate TLS and return an explicit HTTP 501 JSON status
  until those capabilities are implemented. They must not claim successful service availability.

## User entry points

- A counterparty starts at `https://auth.ods.uz/register?kind=partner`. The first user verifies
  email, signs in, opens `https://partners.ods.uz`, registers the organization and becomes its
  owner. The application then opens the organization portal such as `https://company.ods.uz`.
- A platform administrator opens `https://admin.ods.uz`. The portal redirects to the canonical
  login, verifies the `admin` or `security_admin` role, requires a strong passkey or TOTP-authenticated
  session, and then asks for a fresh step-up before loading administrative data.
- `https://accounts.ods.uz` opens the ordinary account dashboard.

Caddy obtains and renews TLS certificates automatically and the production/staging listeners
accept TLS 1.3 only. Do not install Certbot or a second ACME renewal mechanism on the same
listener. Caddy may manage separate certificates per site, avoiding one shared private key across
every subdomain.

TCP ports 80 and 443 and UDP port 443 must be reachable from the Internet. UDP 443 enables HTTP/3.
Before mandatory verification is enabled, Resend domain verification and MX/SPF/DKIM/DMARC records
must be published and `SMTP_PASSWORD` must contain a live API key.

## Deploy

```bash
bash scripts/server-bootstrap.sh /opt/ods-platform
```

Then run the reviewed GitHub `Deploy production` workflow. The deployment script builds the
containers, confirms the newest Flyway migration and polls `/ready`. Before changing application
containers it writes a compressed PostgreSQL dump to `BACKUP_DIR`
(default `/var/backups/ods-platform`) and uploads it to MinIO. After Flyway it calls PostgreSQL `uuidv7()`
and verifies that all 19 domain tables have native UUID internal identifiers. It validates the
production Caddyfile and recreates the Caddy container. A failed backup, migration, proxy
validation or schema assertion stops the deployment. Systemd recovery starts the complete stack
after a VPS reboot, and a timer creates daily backups.

The backend image is built as a layered multi-stage Java 26 image and runs with G1GC. Dynamic CDS
training is intentionally disabled because Temurin 26.0.1 can crash in
`LambdaProxyClassDictionary` while writing an archive for this Spring application. The standard
JRE class-data archive remains available without making production builds depend on that unstable
training path.
