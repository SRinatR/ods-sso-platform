# Security policy

## Reporting a vulnerability

Do not disclose suspected vulnerabilities in a public issue, discussion, pull request, or chat.
Use GitHub private vulnerability reporting for this repository when available. Otherwise contact
the repository owner privately and include:

- the affected endpoint or component;
- reproducible steps;
- expected and observed impact;
- any proof-of-concept data with secrets removed.

Do not test against production accounts or data that you do not own. Do not retain access tokens,
cookies, passwords, private keys, one-time codes, or personal data collected during testing.

## Supported versions

Security fixes are applied to the current `main` branch and the production release derived from it.
Older snapshots and abandoned branches are not supported.

## Immutable security baseline

The CI command `pwsh ./scripts/verify-security-baseline.ps1` protects the invariants below from
silent weakening. The validator is a regression gate, not a substitute for integration tests,
dependency scanning, penetration testing, or an external audit.

| Rule | Enforced invariant | Current implementation status |
|---|---|---|
| SEC-BASE-001 | Authorization Code with mandatory PKCE; `code_challenge_method=S256` | Enforced |
| SEC-BASE-002 | Implicit and password grants are absent | Enforced |
| SEC-BASE-003 | Exact registered redirect URIs; HTTPS except loopback development callbacks; no fragments | Enforced |
| SEC-BASE-004 | First-party cookie contains an opaque random token and is HttpOnly/SameSite=Lax | Enforced; durable session records are currently PostgreSQL-backed rather than Redis-backed |
| SEC-BASE-005 | ID tokens use RS256 and RSA keys are at least 2048 bits | Enforced; multi-key seven-day rotation window remains a separate increment |
| SEC-BASE-006 | Refresh tokens rotate, reuse is detected, sessions are revoked, incident is audited | Enforced; reuse ledger is durable PostgreSQL rather than Redis |
| SEC-BASE-007 | New passwords and OAuth client secrets use Argon2id with `m=131072,t=4,p=4` | Enforced; legacy bcrypt one-time migration is not needed until a legacy import path exists |
| SEC-BASE-008 | Production and staging Caddy listeners accept TLS 1.3 only | Enforced |
| SEC-BASE-009 | Opaque token MACs use HMAC-SHA256 and constant-time comparison | Enforced; TOTP intentionally uses the interoperable OATH HMAC-SHA1 algorithm and is not used as a storage MAC |
| SEC-BASE-010 | Login uses dummy Argon2 verification and a 100–200 ms timing floor; duplicate registration is non-disclosing | Enforced |
| SEC-BASE-011 | Audit details redact known password, token, OTP, client-secret, and private-key fields | Partial; a process-wide logging sanitizer and automated log-content tests remain open |
| SEC-BASE-012 | Login `5/15m`, registration `3/1h`, MFA `3/1m`, with `429` and `Retry-After` | Partial; Redis counters are fixed-window and MFA limit overflow does not yet impose a 30-minute account lock |

## Operational requirements

- Production secrets must be injected outside Git and rotated after suspected disclosure.
- `SESSION_SECRET`, `TOKEN_PEPPER`, JWT keys, TOTP encryption keys, database credentials, SMTP
  credentials, and OAuth client secrets must be unique per environment.
- Database backups must be encrypted, access-controlled, and restoration-tested.
- Flyway migrations must complete before the backend is admitted to readiness.
- Production releases must verify `/ready`, OIDC Discovery, JWKS, Authorization Code + PKCE,
  token exchange, UserInfo, refresh rotation, logout, and cleanup.
