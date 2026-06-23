# Changelog

All notable changes to the ODS SSO Platform are documented here.

## Unreleased

### Added

- RFC 7807-compatible Problem Details fields for API and Spring Security authentication errors.
- Regression coverage for error responses that inherit an incorrect endpoint content type.
- Immutable SEC-BASE verification script and repository security policy.
- Passkey-backed fresh step-up for administrative and MFA-management operations.
- Self-service TOTP disable and backup-code regeneration in the security cabinet.
- Device-specific and cross-device passkey enrollment for laptops, phones and security keys.
- Public human-readable service status and expanded privacy pages.
- Isolated navigation for personal, counterparty and system-administration portals.
- Counterparty self-service OIDC settings for client type, scopes, callback and logout URLs.

### Changed

- API errors now use `application/problem+json` while retaining the existing `error`, `message`,
  `details`, and `request_id` compatibility fields.
- Argon2id password hashing now uses `m=131072,t=4,p=4`.
- Login rate limiting is five attempts per fifteen minutes.
- Login performs dummy password verification and enforces a 100–200 ms timing floor.
- Duplicate registration no longer discloses whether an email is already registered.
- Production and staging Caddy configurations require TLS 1.3.
- The Caddy runtime image is pinned to `2.11.1-alpine`.
- Passkey reauthentication upgrades the current session instead of creating duplicate sessions.
- Passkey sessions no longer advertise the password authentication factor.
- Disabling TOTP revokes other sessions and safely downgrades the current password session.
- Registration uses separate burst and daily anti-abuse limits instead of a three-attempt hourly lockout.
- Repeated registration of an unverified account issues a fresh verification message.
- Session and connected-application pages redirect only after an actual authentication failure.
- Session activity uses an atomic throttled touch, avoiding optimistic-lock races between parallel account requests.
- Email screens distinguish provider acceptance from guaranteed mailbox delivery.
- Administrative sessions explain the required passkey or TOTP setup instead of appearing logged out.
