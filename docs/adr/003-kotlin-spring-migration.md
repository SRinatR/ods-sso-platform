# ADR 003: Kotlin and Spring as the sole backend runtime

## Status

Accepted on 2026-06-20.

## Decision

ODS SSO uses Java 26, Kotlin 2.4, Spring Boot 4.1 and Spring Security 7.1 as its only backend
runtime. OAuth/OIDC protocol behavior is delegated to Spring Authorization Server.

The prior Python/FastAPI backend is removed rather than operated in parallel because the project
has no production data or compatibility obligation requiring a strangler migration.

## Consequences

- Flyway replaces Alembic.
- Spring Authorization Server tables own authorization, token and consent protocol state.
- Existing account JSON endpoints and the `ods_session` cookie remain compatible with the Next.js
  frontend.
- PostgreSQL, Redis and Kafka become mandatory production dependencies.
- The modular monolith keeps transactional consistency while bounded contexts remain extractable.
- Standalone Spring Authorization Server `1.x` is not pinned; Authorization Server is part of
  Spring Security 7.1.
