# ODS SSO Platform

Production-oriented MVP of an Identity Core and OAuth 2.0/OpenID Connect provider.

## Implemented

- Registration, email verification, password reset, login, logout and logout-all
- Durable session management and login history
- TOTP MFA, one-time backup codes and admin step-up authentication
- Authorization Code Flow with mandatory PKCE S256
- RS256 access/ID tokens, JWKS, Discovery and UserInfo
- Opaque refresh token rotation, reuse detection, revocation and introspection
- Consent screen, versioned consent storage, connected applications and revoke
- Admin Console APIs and UI for users, clients, sessions, audit and policies
- Argon2id, AES-256-GCM, HMAC-SHA256, security headers, rate limits and brute-force lock
- Alembic migration, OpenAPI document, backend/frontend tests and gated CI/CD

Phase 2 functions such as SCIM, webhooks, SAML, passkeys, PAR/JAR/JARM, DPoP and mTLS are intentionally absent.

## Local start

```bash
cp .env.example .env
docker compose up --build
```

Before a non-development deployment, set independent high-entropy values for:

- `SESSION_SECRET`
- `TOKEN_PEPPER`
- `TOTP_ENCRYPTION_KEY` — URL-safe base64 for exactly 32 bytes
- `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY`
- PostgreSQL credentials
- SMTP credentials
- optional bootstrap admin credentials

Endpoints:

- Account UI: `http://account.localhost`
- API and OpenAPI: `http://localhost:8080/docs`
- Discovery: `http://localhost:8080/.well-known/openid-configuration`
- JWKS: `http://localhost:8080/.well-known/jwks.json`

The backend container runs `alembic upgrade head` before starting the service.

## Development checks

Backend:

```bash
cd backend
pip install -r requirements-dev.txt
ruff check app scripts tests
ruff format --check app scripts tests
mypy app scripts tests --no-incremental
pytest -q --cov=app --cov-branch --cov-fail-under=80
pytest -q \
  --cov=app.services.identity \
  --cov=app.services.mfa \
  --cov=app.services.oauth \
  --cov=app.services.session \
  --cov-branch --cov-fail-under=90
```

Frontend:

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm run build
```

## OAuth endpoints

- `GET /authorize`
- `POST /token`
- `GET /userinfo`
- `POST /revoke`
- `POST /introspect`
- `GET /.well-known/openid-configuration`
- `GET /.well-known/jwks.json`

Confidential clients use `client_secret_basic` or `client_secret_post`. Public clients use `none`. PKCE method `S256`, `state` and OIDC `nonce` are mandatory.

