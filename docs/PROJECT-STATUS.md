# ODS SSO Platform — implementation status

Last updated: 2026-06-19.

## MVP status

| Area | Status |
|---|---|
| Identity Core | Implemented |
| Email verification and password reset | Implemented with SMTP and development outbox |
| Sessions and login history | Implemented |
| TOTP, backup codes and step-up | Implemented |
| OAuth 2.0 / OpenID Connect | Implemented |
| Refresh rotation and reuse detection | Implemented |
| Consent and connected applications | Implemented |
| Admin Console | Implemented |
| Security headers, rate limits and audit | Implemented |
| Alembic migration and OpenAPI | Implemented |
| Backend lint, types and tests | Passing locally |
| Frontend lint, types and production build | Passing locally |
| Docker build | Defined in CI; local Docker is not installed on this workstation |
| Staging deployment | Requires repository SSH secrets, server `.env` and DNS |

## Verified locally

- `ruff check`: passed
- strict `mypy`: passed
- backend tests: 47 passed
- overall backend coverage: at least 80%
- Identity/MFA/OAuth/session service coverage: at least 90%
- frontend ESLint: passed
- frontend TypeScript: passed
- Next.js production build: passed
- Alembic upgrade → downgrade → upgrade: passed on SQLite

CI repeats database tests and migration validation against PostgreSQL 16 and Redis 7.

## Deployment prerequisites

1. Configure GitHub SSH secrets described in `docs/GITHUB-CICD.md`.
2. Copy `.env.staging.example` to `.env` on the server.
3. Populate all required secrets without committing them.
4. Configure `staging.api.ods.uz` and `staging.account.ods.uz`.
5. Push to `main`; deploy starts only after the full CI workflow succeeds.

