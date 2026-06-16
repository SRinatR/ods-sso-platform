# ODS SSO Platform — Pilot (Tatarlar)

Минимальный SSO для интеграции с Tatarlar Platform на staging.

## Быстрый старт (локально)

```bash
cd ods-platform
cp .env.example .env
docker compose up -d --build
```

- API: http://localhost (или http://localhost:8080 через Caddy)
- Account portal: http://account.localhost/login (добавьте в hosts: `127.0.0.1 account.localhost`)

## Staging deploy (CI/CD)

Push в `main` → автодеплой на VPS через GitHub Actions.

1. Настройте secrets — см. [docs/GITHUB-CICD.md](docs/GITHUB-CICD.md)
2. Один раз на сервере: `scripts/server-bootstrap.sh` + `.env`
3. Push в GitHub

Ручной deploy без CI:

```bash
./scripts/deploy-staging.sh
```

## Pilot accounts

| Email | Password | Назначение |
|-------|----------|------------|
| pilot-admin@ods.uz | PilotAdmin2026! | Admin flow |
| pilot-member@ods.uz | PilotMember2026! | Web/profile flow |

## Tatarlar OAuth client

- `client_id`: `ods_tatarlar_staging`
- `client_secret`: см. лог backend при первом запуске или `.env` → `TATARLAR_CLIENT_SECRET`

## Endpoints (staging)

| Endpoint | URL |
|----------|-----|
| Discovery | https://staging.api.ods.uz/.well-known/openid-configuration |
| Authorize | https://staging.api.ods.uz/api/v1/auth/oauth/authorize |
| Token | https://staging.api.ods.uz/api/v1/auth/oauth/token |
| JWKS | https://staging.api.ods.uz/.well-known/jwks.json |
| UserInfo | https://staging.api.ods.uz/api/v1/auth/oauth/userinfo |
| Login UI | https://staging.account.ods.uz/login |
