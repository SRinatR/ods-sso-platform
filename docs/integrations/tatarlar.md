# Tatarlar Platform — SSO Integration (Staging)

## Endpoints

```
Discovery:  https://staging.api.ods.uz/.well-known/openid-configuration
Issuer:     https://staging.api.ods.uz
Authorize:  https://staging.api.ods.uz/api/v1/auth/oauth/authorize
Token:      https://staging.api.ods.uz/api/v1/auth/oauth/token
JWKS:       https://staging.api.ods.uz/.well-known/jwks.json
UserInfo:   https://staging.api.ods.uz/api/v1/auth/oauth/userinfo
```

## Client credentials

| Parameter | Value |
|-----------|-------|
| client_id | `ods_tatarlar_staging` |
| client_type | Confidential |
| token_endpoint_auth | `client_secret_post` |
| PKCE | Required, S256 |

**client_secret** — передаётся через защищённый канал (Telegram / 1Password).

## Redirect URIs (whitelist)

```
https://api-staging.tatarlar.uz/api/v1/auth/sso/callback
http://localhost:3002/api/v1/auth/sso/callback
https://api.tatarlar.uz/api/v1/auth/sso/callback
```

## Authorization request

```
GET https://staging.api.ods.uz/api/v1/auth/oauth/authorize
  ?client_id=ods_tatarlar_staging
  &redirect_uri=https://api-staging.tatarlar.uz/api/v1/auth/sso/callback
  &response_type=code
  &scope=openid+email+profile
  &state={your_state}
  &code_challenge={S256_challenge}
  &code_challenge_method=S256
  &kc_idp_hint=google|yandex|telegram   # optional
```

## Token exchange

```
POST https://staging.api.ods.uz/api/v1/auth/oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code={code}
&redirect_uri=https://api-staging.tatarlar.uz/api/v1/auth/sso/callback
&client_id=ods_tatarlar_staging
&client_secret={secret}
&code_verifier={verifier}
```

Response:

```json
{
  "access_token": "...",
  "id_token": "...",
  "token_type": "Bearer",
  "expires_in": 900,
  "refresh_token": "...",
  "scope": "openid email profile"
}
```

## id_token claims (MUST validate)

| Claim | Required | Notes |
|-------|----------|-------|
| iss | yes | `https://staging.api.ods.uz` |
| aud | yes | `ods_tatarlar_staging` |
| sub | yes | `usr_xxx` — stable user ID |
| email | yes | always present |
| email_verified | recommended | boolean |
| name | optional | display name |
| exp, iat | yes | standard JWT |

Validate signature via JWKS (`RS256`).

## Test accounts

| Email | Password |
|-------|----------|
| pilot-admin@ods.uz | PilotAdmin2026! |
| pilot-member@ods.uz | PilotMember2026! |

## IdP hints

Optional query param `kc_idp_hint=google|yandex|telegram` on authorize — forwarded to identity provider (Keycloak).

## Logout

Federated logout (`end_session_endpoint`) — **v0.2**. On pilot, use local logout on Tatarlar side.

## Flow diagram

```
User → Tatarlar /auth/sso/start
     → ODS /oauth/authorize
     → ODS account login (if needed)
     → callback to Tatarlar API with code
     → Tatarlar POST /oauth/token
     → validate id_token → session
```
