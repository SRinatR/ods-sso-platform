# Partner tenancy and RBAC

## Invariants

- An ODS account is a person, not a company.
- One account may own or participate in several partner organizations.
- Every organization has one immutable primary owner.
- Organization data and mutations are available only on `https://{slug}.ods.uz`.
- `https://auth.ods.uz` is only the identity provider: login, registration, authorization,
  consent, token and logout flows.
- The central `https://partners.ods.uz` page only lists organizations and creates new ones.
- `https://auth.ods.uz/partner` is a backward-compatible redirect and is not the canonical
  partner cabinet URL.
- OAuth clients, members, roles, secrets and future analytics are scoped to one organization.
- System administration at `admin.ods.uz` is separate from partner administration.
- `contact_email` is a notification/contact address. It does not grant access. Access is granted
  only by an active membership row for the user's ODS account.

## Organization roles

| ODS role | Meaning | Default application permissions |
|---|---|---|
| `owner` | Primary company administrator | organization, members, applications, read/write |
| `admin` | Legacy delegated administrator | members, applications, read/write |
| `editor` | Creates and edits partner content | read/write |
| `user` | Uses the partner service | read/use |
| `viewer` | Read-only user; shown as “reader” in UI | read |

Only the owner can add, disable or change organization members. A member must first register an
ODS account. Invitation-by-email is a separate delivery increment; the API currently returns a
diagnostic error when the email is not registered. If a registered user opens an existing
`https://{slug}.ods.uz` cabinet without membership, the API returns `403 partner_workspace_forbidden`
instead of showing the organization creation form.

## OIDC claims

For an OAuth client created inside a partner organization, ODS adds these claims to access and
ID tokens when the user has an active membership:

```json
{
  "organization_id": "org_...",
  "organization_slug": "tatarlar",
  "organization_role": "editor",
  "roles": ["editor"],
  "permissions": ["content:read", "content:write"]
}
```

The relying party must validate issuer, audience and `organization_slug`. It may map the
organization role to its own application permissions, but it must not accept a role for another
organization.

## Analytics boundary

Partner analytics is scoped by `organization_id` and `client_id`. The cabinet shows a 30-day
aggregate view for organization owners and admins:

- successful SSO logins;
- OAuth security and token failures;
- unique active users;
- consent acceptance and denial;
- token issuance and refresh failures;
- session revocations;
- member and role changes;
- client configuration and secret rotations.

Analytics must be aggregated and must not expose passwords, tokens, secrets, WebAuthn material,
raw IP addresses or full user-agent strings.
