# Production deployment

Production deploys are owned by the GitHub Environment named `production`.
No production secret is committed to the repository. GitHub Actions writes a
restricted `/etc/ods-platform/production.env` file on the VPS with mode `0600`;
this file is generated on every deployment and is not the source of truth.

## Release flow

Production uses a dedicated protected branch:

1. Changes are merged into `main`.
2. A successful `Quality Gate` on `main` creates or reuses a `main → prod`
   promotion pull request.
3. Merging that pull request runs `Quality Gate` on the exact `prod` commit.
4. A successful `prod` gate automatically starts `Deploy production`.

The production environment accepts deployments only from `prod`. A manual
deployment is also supported, but the workflow must be dispatched from `prod`.
Pull request code is never deployed before it is merged.

## GitHub location

Open:

`Repository → Settings → Environments → production`

Use **Environment secrets** for confidential values and **Environment variables**
for routing and deployment metadata.

### Required environment secrets

| Name | Value |
| --- | --- |
| `VPS_SSH_PRIVATE_KEY` | Private key used only for production deploys |
| `VPS_KNOWN_HOSTS` | Output of `ssh-keyscan -H 94.232.44.189` |
| `POSTGRES_PASSWORD` | Existing production PostgreSQL password |
| `SESSION_SECRET` | Independent URL-safe random secret, at least 32 characters |
| `TOKEN_PEPPER` | Independent URL-safe random secret, at least 32 characters |
| `TOTP_ENCRYPTION_KEY` | URL-safe Base64 encoding of exactly 32 random bytes |
| `JWT_PRIVATE_KEY` | Base64-encoded PKCS#8 RSA private key |
| `JWT_PUBLIC_KEY` | Base64-encoded X.509 RSA public key |
| `BOOTSTRAP_ADMIN_PASSWORD` | Temporary recovery/bootstrap administrator password |
| `RESEND_API_KEY` | Resend API key used as the SMTP password |
| `MINIO_ROOT_USER` | MinIO root access key |
| `MINIO_ROOT_PASSWORD` | Long random MinIO root secret |

### Optional off-VPS backup secrets

These values are required for disaster recovery if the VPS is completely lost.
A MinIO volume on the same VPS is not an off-site backup.

| Name | Value |
| --- | --- |
| `BACKUP_S3_ENDPOINT` | External MinIO/S3 HTTPS endpoint |
| `BACKUP_S3_ACCESS_KEY` | External storage access key |
| `BACKUP_S3_SECRET_KEY` | External storage secret key |

Set `BACKUP_S3_BUCKET` as an Environment variable when external storage is used.

### Environment variables

| Name | Production value |
| --- | --- |
| `VPS_HOST` | `94.232.44.189` |
| `VPS_PORT` | `22` |
| `VPS_USER` | `root` until a dedicated deploy user is created |
| `VPS_APP_DIR` | `/opt/ods-platform` |
| `BOOTSTRAP_ADMIN_RECONCILE` | `false`; enable only for an intentional one-time recovery |
| `BACKUP_S3_BUCKET` | External bucket name, or leave unset |

## Resend

The workflow configures:

- SMTP host: `smtp.resend.com`
- SMTP port: `587`
- SMTP username: `resend`
- sender: `ODS Identity <no-reply@ods.uz>`

The `ods.uz` sending domain must be verified in Resend. Registration is blocked
when email delivery is unavailable because email verification is mandatory in
production.

## SSH recovery

`scripts/server-bootstrap.sh` installs and enables OpenSSH and preserves inbound
TCP ports `22`, `80`, and `443` in UFW. If SSH is already unavailable, open the
provider's web/VNC console and run:

```bash
apt-get update
apt-get install -y openssh-server
systemctl unmask ssh
systemctl enable --now ssh
ufw allow 22/tcp
ufw reload
ss -lntp | grep ':22'
systemctl --no-pager --full status ssh
```

Inbound TCP `22` must also be allowed in the provider-level network firewall.
Do not restrict port `22` to a single administrator IP while deployments use
GitHub-hosted runners, because their source addresses are not fixed.

After a VPS reinstall, add the production deployment public key to root before
the first workflow run:

```bash
install -d -m 700 /root/.ssh
printf '%s\n' 'DEPLOYMENT_PUBLIC_KEY' >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys
```

The workflow detects an empty VPS, installs Git and Docker, clones the
repository into `/opt/ods-platform`, writes the restricted runtime environment,
and then performs the normal deployment.

## Reboot recovery

`ods-platform.service` runs after Docker and network readiness, recreates the
Compose stack if required, waits for `/ready`, and then recreates Caddy.
`ods-platform-backup.timer` creates a daily PostgreSQL dump and uploads it to
local MinIO and, when configured, the external S3/MinIO target.

## Manual restore

Restore is deliberately guarded:

```bash
cd /opt/ods-platform
RESTORE_CONFIRM=restore-ods-production \
  ENV_FILE=/etc/ods-platform/production.env \
  scripts/restore.sh ods_sso-YYYYMMDDTHHMMSSZ.sql.gz
```

Use `RESTORE_SOURCE=remote` to restore from the external backup target.
