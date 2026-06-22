# Tatarlar OIDC integration

## Metadata

Use the environment Discovery URL:

```text
https://auth.ods.uz/.well-known/openid-configuration
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

The client belongs to the partner organization that created it. Updating the
organization's display name, legal name, website, or contact details does not
change the client ID or secret. Moving an integration to a different legal
organization requires creating a new application for that organization,
switching the consumer to the new credentials, and then disabling the old
application. Credentials are never copied between organizations automatically.

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
