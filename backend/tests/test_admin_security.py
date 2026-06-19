import pyotp
import pytest
from httpx import AsyncClient

from app.services.mail import get_mail_service

pytestmark = [pytest.mark.integration, pytest.mark.security]


async def login_admin_with_mfa(client: AsyncClient) -> str:
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": "admin@example.com", "password": "AdminPassword2026!"},
    )
    assert login.status_code == 200
    setup = await client.post("/api/v1/auth/mfa/totp/setup")
    secret = setup.json()["secret"]
    enabled = await client.post(
        "/api/v1/auth/mfa/totp/enable", json={"code": pyotp.TOTP(secret).now()}
    )
    assert enabled.status_code == 200
    step_up = await client.post(
        "/api/v1/auth/step-up",
        json={"password": "AdminPassword2026!", "code": pyotp.TOTP(secret).now()},
    )
    assert step_up.status_code == 200
    return secret


async def test_security_headers_and_discovery(client: AsyncClient) -> None:
    response = await client.get("/.well-known/openid-configuration")
    assert response.status_code == 200
    assert response.json()["code_challenge_methods_supported"] == ["S256"]
    assert response.headers["x-frame-options"] == "DENY"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert "max-age=63072000" in response.headers["strict-transport-security"]
    assert response.headers["permissions-policy"]
    jwks = await client.get("/.well-known/jwks.json")
    assert jwks.json()["keys"][0]["alg"] == "RS256"


async def test_admin_requires_mfa_step_up_and_manages_resources(client: AsyncClient) -> None:
    await client.post(
        "/api/v1/auth/login",
        json={"email": "admin@example.com", "password": "AdminPassword2026!"},
    )
    denied = await client.get("/api/v1/admin/dashboard")
    assert denied.status_code == 403
    await client.post("/api/v1/auth/logout")

    await login_admin_with_mfa(client)
    dashboard = await client.get("/api/v1/admin/dashboard")
    assert dashboard.status_code == 200
    assert dashboard.json()["users_total"] >= 1

    created = await client.post(
        "/api/v1/admin/oauth-clients",
        json={
            "name": "Admin Created Client",
            "redirect_uris": ["https://admin-client.example/callback"],
            "allowed_scopes": ["openid", "email"],
            "is_public": False,
            "token_endpoint_auth_method": "client_secret_basic",
        },
    )
    assert created.status_code == 201
    assert created.json()["client_secret"]
    client_id = created.json()["client_id"]
    rotated = await client.post(f"/api/v1/admin/oauth-clients/{client_id}/rotate-secret")
    assert rotated.status_code == 200
    assert rotated.json()["client_secret"] != created.json()["client_secret"]

    policy = await client.put(
        "/api/v1/admin/security-policies/custom",
        json={"value": {"enabled": True}},
    )
    assert policy.status_code == 200
    assert policy.json()["value"]["enabled"] is True

    audit = await client.get("/api/v1/admin/audit")
    event_types = {item["event_type"] for item in audit.json()}
    assert "OAUTH_CLIENT_CREATED" in event_types
    assert "SECURITY_POLICY_UPDATED" in event_types


async def test_validation_errors_use_unified_schema(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/register", json={})
    assert response.status_code == 422
    payload = response.json()
    assert set(payload) == {"error", "message", "details", "request_id"}
    assert payload["error"] == "validation_error"
    assert payload["details"]
    assert get_mail_service().outbox == []
