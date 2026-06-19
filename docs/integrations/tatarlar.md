# Tatarlar OIDC integration

## Metadata

Use the environment Discovery URL:

```text
https://staging.api.ods.uz/.well-known/openid-configuration
```

Canonical endpoints are discovered dynamically. Do not hard-code signing keys; cache and refresh JWKS by `kid`.

## Client contract

- Authorization Code Flow
- PKCE S256 is mandatory
- `state` and `nonce` are mandatory
- scopes: `openid profile email offline_access`
- client authentication is configured by the administrator
- redirect URI must exactly match a registered URI

The client secret is shown only once when created or rotated and must be transferred through an approved secret manager.

## ID Token validation

Validate:

- RS256 signature and `kid`
- exact issuer
- audience equals the assigned client ID
- expiry and issued-at
- nonce equals the authorization request
- stable `sub`

Email is returned only when the `email` scope is approved. It must not be used as the durable identity key.

## Revocation

Users can revoke Tatarlar from Connected Applications. Revocation invalidates active access and refresh tokens for that user/client pair. The client must handle an inactive introspection response by ending its local session.

