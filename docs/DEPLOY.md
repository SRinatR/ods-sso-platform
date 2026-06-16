# Deploy на staging VPS

## 1. DNS

```
staging.api.ods.uz     → A → IP VPS
staging.account.ods.uz → A → IP VPS
```

## 2. Подготовка сервера

```bash
# Ubuntu 22.04+
sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
sudo usermod -aG docker $USER
```

## 3. Клонировать / скопировать проект

```bash
cd /opt
git clone <repo> ods-platform   # или scp папку ods-platform
cd ods-platform
```

## 4. Конфигурация

```bash
cp .env.staging.example .env
cp Caddyfile.staging Caddyfile

# Сгенерировать секреты
openssl rand -base64 32   # → POSTGRES_PASSWORD, SESSION_SECRET, TATARLAR_CLIENT_SECRET
openssl genrsa -out jwt.pem 2048
openssl rsa -in jwt.pem -pubout -out jwt.pub.pem
# Вставить в .env как JWT_PRIVATE_KEY / JWT_PUBLIC_KEY (с \n)
```

Обязательно задать в `.env`:
- `POSTGRES_PASSWORD`
- `KEYCLOAK_ADMIN_PASSWORD`
- `KEYCLOAK_CLIENT_SECRET` (и совпадающий secret в Keycloak — обновить `keycloak/realm-ods.json` или через admin UI)
- `SESSION_SECRET`
- `TATARLAR_CLIENT_SECRET`

## 5. Запуск

```bash
chmod +x scripts/deploy-staging.sh
./scripts/deploy-staging.sh
```

Проверка:

```bash
curl -s https://staging.api.ods.uz/health
curl -s https://staging.api.ods.uz/.well-known/openid-configuration | jq .
```

## 6. Отправить Tatarlar

См. [docs/integrations/tatarlar.md](integrations/tatarlar.md)

- `client_id`: `ods_tatarlar_staging`
- `client_secret`: из `.env` → `TATARLAR_CLIENT_SECRET`
- Test accounts: `pilot-admin@ods.uz` / `PilotAdmin2026!`

## 7. Проверка OAuth flow

1. Tatarlar admin → «Войти через SSO»
2. Redirect на `staging.api.ods.uz/.../authorize`
3. Login на `staging.account.ods.uz`
4. Callback на `api-staging.tatarlar.uz/.../callback` с `code`
5. Tatarlar обменивает code → получает `id_token` с `email`
