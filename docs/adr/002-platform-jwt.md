# ADR-002: Platform JWT

## Status
Accepted

## Context
Partners must not receive raw Keycloak tokens. ODS issues its own JWTs signed with platform keys.

## Decision
- Issuer: `https://staging.api.ods.uz` (staging) / `https://api.ods.uz` (prod)
- Algorithm: RS256
- `sub` = canonical PostgreSQL user ID (`usr_xxx`)
- `id_token` always includes `email`, `aud` (= client_id), `iss`

## Consequences
- Partners validate tokens against ODS JWKS, not Keycloak
- Token revocation and session management owned by ODS API
