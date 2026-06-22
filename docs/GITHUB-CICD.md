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
3. Pull the exact commit on the VPS.
4. Run `scripts/deploy.sh`.
5. Verify backup creation, the latest Flyway migration, UUIDv7 schema assertions, `/ready`,
   OIDC Discovery, JWKS and the public UI.
6. Execute the complete Authorization Code + PKCE OIDC end-to-end flow.

The server `.env`, private keys, passwords and client secrets are never committed. Deployment
credentials are maintained outside the repository.
