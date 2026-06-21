# GitHub delivery status

Automated CI/CD is intentionally deferred for the first partner pilot.

The current release procedure is controlled and manual:

1. Build the Kotlin executable JAR locally.
2. Run frontend lint, TypeScript checks and the Next.js production build locally.
3. Commit and push the reviewed tree to `main`.
4. Pull the exact commit on the VPS.
5. Run `scripts/deploy.sh`.
6. Verify the latest successful Flyway migration, `/ready`, OIDC Discovery and the public UI.

The server `.env` is never committed. GitHub Actions will be restored after the pilot flow and
production runtime requirements are stable.
