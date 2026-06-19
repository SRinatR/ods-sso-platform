# ADR-002: Platform tokens

## Status

Accepted.

## Decision

- Access and ID tokens are JWTs signed with RS256 and a `kid`.
- Public verification keys are exposed through JWKS.
- `sub` is the stable ODS user identifier; email is not an identity key.
- Access tokens live for 15 minutes by default.
- Refresh tokens are opaque `id.secret` values. Only the secret hash is stored.
- Every refresh rotates. Reuse revokes the token family, the client's access tokens and user sessions.
- ID Token and UserInfo claims are limited by scopes and active consent.

