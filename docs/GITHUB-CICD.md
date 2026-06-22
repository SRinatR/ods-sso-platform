# GitHub delivery

GitHub Actions runs the repository quality gate for pull requests, `main`, and `codex/**`
branches.

## Required checks

- immutable SEC-BASE regression validation;
- Kotlin tests and the 80% JaCoCo line-coverage gate;
- executable Spring Boot JAR build;
- Docker 26+ availability and production image build with CDS training;
- frontend ESLint, TypeScript and Next.js production build.

## Release procedure

Production deployment remains controlled and explicit:

1. Merge or select the exact reviewed commit.
2. Confirm the GitHub Actions quality gate is green.
3. Open **Actions → Deploy production → Run workflow** for the reviewed commit.
4. GitHub installs the restricted runtime environment and runs `scripts/deploy.sh` over SSH.
5. Verify backup creation, the latest Flyway migration, UUIDv7 schema assertions, `/ready`,
   OIDC Discovery, JWKS and the public UI.
6. Execute the complete Authorization Code + PKCE OIDC end-to-end flow.

The repository `.env`, private keys, passwords and client secrets are never committed. GitHub
Environment `production` is the source of truth. The generated VPS file
`/etc/ods-platform/production.env` is mode `0600`. See
[`production-deployment.md`](production-deployment.md).
