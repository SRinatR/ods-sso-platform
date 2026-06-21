# ODS SSO Platform

Enterprise-oriented Identity Provider and OAuth 2.1/OpenID Connect server.

The Python/FastAPI implementation has been removed. The only backend runtime is Kotlin/Spring.

## Stack

- Java 26.0.1 with virtual threads and scoped values
- Kotlin 2.4.0
- Spring Boot 4.1.0 and Spring Security 7.1.0
- Spring Authorization Server integrated into Spring Security
- PostgreSQL 18.4 and Flyway 12.4
- Redis 8.6.3
- Kafka 4.3 broker with Spring Boot-managed Kafka 4.2.1 client
- OpenTelemetry Collector 0.154, Prometheus 3.12 and Grafana 13.0
- Next.js 16 and React 19

## Implemented

- Registration, email verification, password reset, login and logout
- Durable opaque-cookie sessions and login history
- TOTP MFA, one-time backup codes and admin step-up authentication
- OAuth 2.1 Authorization Code flow with mandatory PKCE S256
- OpenID Connect Discovery, UserInfo, JWKS and RS256 ID tokens
- Refresh-token rotation, HMAC reuse ledger, session revocation and Spring Authorization Server persistence
- Consent and connected-application revocation
- Tenant isolation through `tenant_id` and Java ScopedValue request context
- Risk assessment and device fingerprint records
- Argon2id password hashing and AES-256-GCM secret encryption
- Tamper-evident audit hash chain
- Transactional domain-event outbox and Kafka publisher
- Admin APIs for users, clients, sessions, audit and policies
- Partner self-service: organization registration and OIDC application provisioning
- Flyway schema, OpenAPI UI, Actuator, Prometheus and OTLP tracing
- Migration-before-readiness deployment checks

Federation provider configuration and key-management metadata are modeled. LDAP, Entra ID,
SAML, SCIM, passkey ceremonies, HSM/KMS adapters and multi-region deployment remain separate
delivery increments; they are not represented as completed features.

## Local start

```bash
cp .env.example .env
docker compose up --build
```

Endpoints:

- Account UI: `http://account.localhost`
- API: `http://localhost:8080`
- OpenAPI: `http://localhost:8080/docs`
- Discovery: `http://localhost:8080/.well-known/openid-configuration`
- JWKS: `http://localhost:8080/.well-known/jwks.json`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3001`

For the first partner pilot, the standard flow is:

1. Create a personal ODS account.
2. Open **Partner workspace** and register the organization.
3. Create an OIDC application and enter its exact callback URL.
4. Save the generated client secret immediately.
5. Integrate through the published Discovery URL using Authorization Code + PKCE S256.

Flyway migrations run before Spring reports the application as ready.

## Development checks

Backend:

```bash
cd backend-kotlin
./gradlew clean test bootJar
```

Frontend:

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm run build
```

## Architecture

The backend is a modular monolith with bounded-context packages:

- `identity`
- `mfa`
- `session`
- `consent`
- `admin`
- `audit`
- `risk`
- `events`
- `tenant`
- `persistence`

This keeps one transactional deployment while preserving boundaries for future service extraction.
