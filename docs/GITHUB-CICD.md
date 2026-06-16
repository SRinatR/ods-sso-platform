# GitHub Actions — secrets для deploy staging

Добавьте в **Settings → Secrets and variables → Actions**:

| Secret | Пример | Описание |
|--------|--------|----------|
| `SSH_HOST` | `203.0.113.10` | IP или hostname VPS |
| `SSH_USER` | `root` | SSH пользователь |
| `SSH_PRIVATE_KEY` | `-----BEGIN OPENSSH...` | Приватный ключ (полностью) |
| `DEPLOY_PATH` | `/opt/ods-platform` | Путь на сервере |
| `SSH_PORT` | `22` | Опционально |

## Первый раз на сервере

```bash
# С вашего ПК — скопировать bootstrap + example env
scp -r ods-platform root@YOUR_VPS:/opt/ods-platform
ssh root@YOUR_VPS 'bash /opt/ods-platform/scripts/server-bootstrap.sh'

# Отредактировать секреты на сервере
ssh root@YOUR_VPS 'nano /opt/ods-platform/.env'
```

Обязательно в `.env` на сервере:
- `POSTGRES_PASSWORD`
- `KEYCLOAK_ADMIN_PASSWORD`
- `KEYCLOAK_CLIENT_SECRET` (совпадает с `keycloak/realm-ods.json` или обновите realm)
- `SESSION_SECRET` (мин. 32 символа)
- `TATARLAR_CLIENT_SECRET`
- `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (openssl genrsa 2048)

## Deploy

Каждый push в `main` → GitHub Actions копирует код на VPS и запускает `docker compose up -d --build`.

Ручной deploy: **Actions → Deploy Staging → Run workflow**

## DNS

```
staging.api.ods.uz     → A → VPS IP
staging.account.ods.uz → A → VPS IP
```
