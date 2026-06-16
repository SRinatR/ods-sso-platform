# ADR-001: Identity Independence

## Status
Accepted

## Context
Keycloak is a temporary Identity Core. Application code must not depend on Keycloak APIs or schemas directly.

## Decision
All identity operations go through the `IdentityProvider` interface. Only `keycloak_provider.py` may import or call Keycloak.

## Consequences
- Migration off Keycloak requires swapping the provider implementation
- Partners never integrate with Keycloak directly
