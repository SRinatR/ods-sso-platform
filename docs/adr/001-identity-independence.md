# ADR-001: First-party Identity Core

## Status

Accepted on 2026-06-19.

## Decision

ODS owns the Identity Core directly. PostgreSQL is the durable source of truth for users, verification/reset tokens, sessions, MFA methods, backup codes, OAuth clients, tokens, consents and audit events.

Redis is limited to TTL data, rate limiting and caches. It is never the only copy of durable consent, refresh token or audit state.

## Consequences

- Passwords use Argon2id.
- TOTP secrets use AES-256-GCM.
- User-facing opaque tokens store only HMAC-SHA256 secret hashes.
- External applications integrate only through ODS OAuth/OIDC endpoints.
- No runtime dependency on Keycloak or another identity product remains.

