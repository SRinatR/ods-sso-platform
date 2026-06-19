# GitHub CI/CD

## CI gates

`.github/workflows/ci.yml` runs:

1. Ruff lint and formatting
2. Mypy typecheck
3. Backend tests with PostgreSQL and Redis
4. Overall and auth/OAuth coverage thresholds
5. Dedicated OAuth flow tests
6. Dedicated security tests
7. Alembic upgrade/downgrade validation
8. OpenAPI drift check
9. Frontend ESLint, TypeScript and production build
10. Current-tree secret scan
11. Backend and frontend Docker builds

## Deploy secrets

Configure repository Actions secrets:

- `SSH_HOST`
- `SSH_USER`
- `SSH_PRIVATE_KEY`
- `SSH_PORT`
- `DEPLOY_PATH`

The server `.env` remains on the server and is backed up before transfer. Deployment runs only after a successful `CI` workflow on `main`, restores `.env`, builds containers, confirms the migration revision and polls `/ready`.

