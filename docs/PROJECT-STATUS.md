# ODS SSO Platform — implementation status

Last updated: 2026-06-23.

## Migration status

| Area | Status |
|---|---|
| Python/FastAPI runtime | Removed |
| Kotlin 2.4 / Java 26 runtime | Implemented |
| Spring Boot 4.1 / Security 7.1 | Implemented |
| Spring Authorization Server | Implemented |
| PostgreSQL 18.4 / Flyway schema | Implemented |
| UUIDv7 internal PK / prefixed public ID model | Implemented |
| Redis rate-limit and MFA state | Implemented |
| Kafka transactional outbox | Implemented |
| Identity, sessions and TOTP MFA | Implemented |
| Passkey/WebAuthn registration, login and step-up | Implemented |
| OTP disable and backup-code regeneration | Implemented |
| Device and cross-device passkey enrollment | Implemented |
| OAuth/OIDC and consent | Implemented |
| Tenant isolation foundation | Implemented |
| Device/risk foundation | Implemented |
| Tamper-evident audit chain | Implemented |
| Actuator, Prometheus and OTLP | Implemented |
| Admin Console API compatibility | Implemented |
| Partner organization self-registration | Implemented |
| Partner OIDC application provisioning | Implemented |
| RFC 7807-compatible API Problem Details | Implemented |
| SEC-BASE immutable CI regression checks | Implemented |
| Production/staging TLS 1.3 minimum | Implemented |
| Human-readable public status and privacy pages | Implemented |
| LDAP, Entra ID and SAML adapters | Not implemented |
| KMS/HSM/Vault adapters | Metadata only |
| SCIM provisioning | Not implemented |

## Verified locally

- JDK 26.0.1: verified
- Gradle wrapper 9.5.0: configured
- clean Kotlin compilation: passed
- backend tests: 78 passed locally; PostgreSQL 18.4 Testcontainers migration test is CI-gated
- JaCoCo line coverage: 80.36% (80% gate passed)
- Configuration Cache: stored and reused
- executable Spring Boot JAR: built
- layered Java 26 production image with G1GC: built
- frontend ESLint: passed
- frontend TypeScript: passed
- Next.js production build: passed

The pilot topology keeps PostgreSQL, Redis, Kotlin backend, Next.js and Caddy in the default
profile. Kafka and the observability stack remain available as optional Compose profiles so the
initial 2 GB VPS is not overloaded.

GitHub Actions enforces the immutable security baseline, backend tests, the 80% coverage gate,
frontend production checks, Docker 26+ and the production backend and MinIO images. A green
`main` commit is promoted by pull request to protected `prod`; merging that pull request deploys
the exact verified `prod` commit through `scripts/deploy.sh`.
