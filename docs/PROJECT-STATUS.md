# ODS SSO Platform — implementation status

Last updated: 2026-06-20.

## Migration status

| Area | Status |
|---|---|
| Python/FastAPI runtime | Removed |
| Kotlin 2.4 / Java 26 runtime | Implemented |
| Spring Boot 4.1 / Security 7.1 | Implemented |
| Spring Authorization Server | Implemented |
| PostgreSQL 18 / Flyway schema | Implemented |
| Redis rate-limit and MFA state | Implemented |
| Kafka transactional outbox | Implemented |
| Identity, sessions and TOTP MFA | Implemented |
| OAuth/OIDC and consent | Implemented |
| Tenant isolation foundation | Implemented |
| Device/risk foundation | Implemented |
| Tamper-evident audit chain | Implemented |
| Actuator, Prometheus and OTLP | Implemented |
| Admin Console API compatibility | Implemented |
| Partner organization self-registration | Implemented |
| Partner OIDC application provisioning | Implemented |
| LDAP, Entra ID and SAML adapters | Not implemented |
| Passkey/WebAuthn ceremonies | Not implemented |
| KMS/HSM/Vault adapters | Metadata only |
| SCIM provisioning | Not implemented |

## Verified locally

- JDK 26.0.1: verified
- Gradle 9.6.0: verified
- clean Kotlin compilation: passed
- backend tests: 13 passed
- executable Spring Boot JAR: built
- frontend ESLint: passed
- frontend TypeScript: passed
- Next.js production build: passed

The pilot topology keeps PostgreSQL, Redis, Kotlin backend, Next.js and Caddy in the default
profile. Kafka and the observability stack remain available as optional Compose profiles so the
initial 2 GB VPS is not overloaded.

GitHub Actions automation and the final security-flow coverage target are deferred until after the
first partner pilot. Local release checks still require a clean backend and frontend production
build before manual deployment.
