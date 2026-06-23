# GitHub delivery

GitHub Actions runs the repository quality gate for pull requests, `main`, and `codex/**`
branches.

## Required checks

- immutable SEC-BASE regression validation;
- Kotlin tests and the 80% JaCoCo line-coverage gate;
- executable Spring Boot JAR build;
- Docker 26+ availability and production backend and MinIO image builds;
- frontend ESLint, TypeScript and Next.js production build.

## Release procedure

Production deployment is controlled by the protected `prod` branch:

1. Merge the reviewed change into `main`.
2. Wait for the `main` quality gate to pass.
3. Review and merge the automatically created `main` to `prod` promotion pull request.
4. The `prod` quality gate rebuilds and verifies the exact merge commit.
5. GitHub installs the restricted runtime environment and runs `scripts/deploy.sh` over SSH.
6. Verify backup creation, the latest Flyway migration, UUIDv7 schema assertions, `/ready`,
   OIDC Discovery, JWKS and the public UI.
7. Execute the complete Authorization Code + PKCE OIDC end-to-end flow.

The repository `.env`, private keys, passwords and client secrets are never committed. GitHub
Environment `production` is the source of truth. The generated VPS file
`/etc/ods-platform/production.env` is mode `0600`. See
[`production-deployment.md`](production-deployment.md).
