import base64
import hashlib
from urllib.parse import parse_qs, urlparse

import pyotp
import pytest
from httpx import ASGITransport, AsyncClient

from app.db.database import AsyncSessionLocal
from app.db.models import OAuthClient
from app.main import app
from app.security import hash_password
from app.services.cache import get_cache
from app.services.mail import get_mail_service

from .test_admin_security import login_admin_with_mfa
from .test_identity_integration import register_and_verify
from .test_oauth_flow import (
    CLIENT_ID,
    CLIENT_SECRET,
    REDIRECT_URI,
    authorize_and_exchange,
    pkce,
    seed_client,
)

pytestmark = pytest.mark.integration


async def login_user(client: AsyncClient) -> None:
    response = await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    assert response.status_code == 200


async def test_registration_verification_and_reset_edge_cases(client: AsyncClient) -> None:
    terms = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "terms@example.com",
            "password": "SecurePassword2026!",
            "name": "Terms User",
            "accept_terms": False,
        },
    )
    assert terms.status_code == 422
    await client.post(
        "/api/v1/auth/register",
        json={
            "email": "user@example.com",
            "password": "SecurePassword2026!",
            "name": "Test User",
            "accept_terms": True,
        },
    )
    duplicate = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "user@example.com",
            "password": "SecurePassword2026!",
            "name": "Test User",
            "accept_terms": True,
        },
    )
    assert duplicate.status_code == 409
    unverified = await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    assert unverified.status_code == 403
    assert (
        await client.post(
            "/api/v1/auth/verify-email",
            json={"token": "evt_invalid_identifier.invalid_secret_value"},
        )
    ).status_code == 400
    assert (
        await client.post("/api/v1/auth/resend-verification", json={"email": "unknown@example.com"})
    ).status_code == 200
    assert (
        await client.post("/api/v1/auth/forgot-password", json={"email": "unknown@example.com"})
    ).status_code == 200
    assert (
        await client.post(
            "/api/v1/auth/reset-password",
            json={
                "token": "prt_invalid_identifier.invalid_secret_value",
                "new_password": "NewPassword2026!",
            },
        )
    ).status_code == 400


async def test_session_revoke_and_logout_all(client: AsyncClient) -> None:
    await register_and_verify(client)
    await login_user(client)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as second:
        await login_user(second)
        sessions = (await client.get("/api/v1/account/sessions")).json()
        other = next(item for item in sessions if not item["current"])
        assert (await client.delete(f"/api/v1/account/sessions/{other['id']}")).status_code == 200
        assert (await second.get("/api/v1/auth/me")).status_code == 401
        await login_user(second)
        assert (await client.post("/api/v1/auth/logout-all")).status_code == 200
        assert (await client.get("/api/v1/auth/me")).status_code == 401
        assert (await second.get("/api/v1/auth/me")).status_code == 401


async def test_mfa_invalid_setup_step_up_and_backup_regeneration(client: AsyncClient) -> None:
    await register_and_verify(client)
    await login_user(client)
    no_mfa_step_up = await client.post(
        "/api/v1/auth/step-up",
        json={"password": "SecurePassword2026!", "code": "123456"},
    )
    assert no_mfa_step_up.status_code == 403
    setup = await client.post("/api/v1/auth/mfa/totp/setup")
    secret = setup.json()["secret"]
    assert (
        await client.post("/api/v1/auth/mfa/totp/enable", json={"code": "000000"})
    ).status_code == 400
    enabled = await client.post(
        "/api/v1/auth/mfa/totp/enable", json={"code": pyotp.TOTP(secret).now()}
    )
    assert enabled.status_code == 200
    wrong_password = await client.post(
        "/api/v1/auth/step-up",
        json={"password": "wrong", "code": pyotp.TOTP(secret).now()},
    )
    assert wrong_password.status_code == 401
    step_up = await client.post(
        "/api/v1/auth/step-up",
        json={"password": "SecurePassword2026!", "code": pyotp.TOTP(secret).now()},
    )
    assert step_up.status_code == 200
    regenerated = await client.post(
        "/api/v1/auth/mfa/backup/regenerate",
        json={"password": "SecurePassword2026!", "code": pyotp.TOTP(secret).now()},
    )
    assert regenerated.status_code == 200
    assert len(regenerated.json()["backup_codes"]) == 10


async def test_oauth_login_redirect_deny_and_existing_consent(client: AsyncClient) -> None:
    await seed_client()
    _verifier, challenge = pkce()
    params = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": "openid email",
        "state": "state",
        "nonce": "nonce",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    redirect = await client.get("/authorize", params=params, follow_redirects=False)
    assert redirect.status_code == 303
    assert "/login?" in redirect.headers["location"]
    await register_and_verify(client)
    await login_user(client)
    consent = await client.get("/authorize", params=params, follow_redirects=False)
    request_id = parse_qs(urlparse(consent.headers["location"]).query)["request_id"][0]
    denied = await client.post(
        "/api/v1/oauth/consent/deny",
        data={"request_id": request_id},
        follow_redirects=False,
    )
    assert "error=access_denied" in denied.headers["location"]
    token_set = await authorize_and_exchange(client)
    second = await client.get("/authorize", params=params, follow_redirects=False)
    assert second.status_code == 303
    assert second.headers["location"].startswith(REDIRECT_URI)
    assert token_set["access_token"]


async def test_token_revocation_and_refresh_introspection(client: AsyncClient) -> None:
    await seed_client()
    await register_and_verify(client)
    await login_user(client)
    token_set = await authorize_and_exchange(client)
    refresh = str(token_set["refresh_token"])
    active_refresh = await client.post(
        "/introspect",
        data={"token": refresh},
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert active_refresh.json()["token_type"] == "refresh_token"
    assert (
        await client.post(
            "/revoke",
            data={"token": refresh},
            auth=(CLIENT_ID, CLIENT_SECRET),
        )
    ).status_code == 200
    inactive_refresh = await client.post(
        "/introspect",
        data={"token": refresh},
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert inactive_refresh.json() == {"active": False}
    assert (
        await client.post(
            "/revoke",
            data={"token": token_set["access_token"]},
            auth=(CLIENT_ID, CLIENT_SECRET),
        )
    ).status_code == 200
    assert (
        await client.get(
            "/userinfo",
            headers={"Authorization": f"Bearer {token_set['access_token']}"},
        )
    ).status_code == 401
    assert (
        await client.post(
            "/revoke",
            data={"token": "malformed"},
            auth=(CLIENT_ID, CLIENT_SECRET),
        )
    ).status_code == 200


async def test_public_and_post_authenticated_clients(client: AsyncClient) -> None:
    async with AsyncSessionLocal() as db:
        db.add_all(
            [
                OAuthClient(
                    client_id="public_client",
                    name="Public",
                    redirect_uris=["http://127.0.0.1/callback"],
                    allowed_scopes=["openid"],
                    grant_types=["authorization_code", "refresh_token"],
                    token_endpoint_auth_method="none",
                    is_public=True,
                    require_pkce=True,
                    enabled=True,
                ),
                OAuthClient(
                    client_id="post_client",
                    client_secret_hash=hash_password("PostClientSecret2026!"),
                    name="Post",
                    redirect_uris=["https://post.example/callback"],
                    allowed_scopes=["openid"],
                    grant_types=["authorization_code", "refresh_token"],
                    token_endpoint_auth_method="client_secret_post",
                    is_public=False,
                    require_pkce=True,
                    enabled=True,
                ),
            ]
        )
        await db.commit()
    public = await client.post(
        "/introspect", data={"token": "invalid", "client_id": "public_client"}
    )
    assert public.status_code == 200
    assert public.json() == {"active": False}
    post = await client.post(
        "/introspect",
        data={
            "token": "invalid",
            "client_id": "post_client",
            "client_secret": "PostClientSecret2026!",
        },
    )
    assert post.status_code == 200
    assert (
        await client.post(
            "/introspect",
            data={
                "token": "invalid",
                "client_id": "post_client",
                "client_secret": "wrong",
            },
        )
    ).status_code == 401


async def test_admin_user_client_session_and_policy_operations(client: AsyncClient) -> None:
    secret = await login_admin_with_mfa(client)
    await register_and_verify(client, "managed@example.com")
    await client.post("/api/v1/auth/logout")
    admin_login = await client.post(
        "/api/v1/auth/login",
        json={"email": "admin@example.com", "password": "AdminPassword2026!"},
    )
    mfa_login = await client.post(
        "/api/v1/auth/mfa/verify",
        json={
            "challenge_token": admin_login.json()["challenge_token"],
            "code": pyotp.TOTP(secret).now(),
            "method": "totp",
        },
    )
    assert mfa_login.status_code == 200
    await client.post(
        "/api/v1/auth/step-up",
        json={"password": "AdminPassword2026!", "code": pyotp.TOTP(secret).now()},
    )
    users = await client.get("/api/v1/admin/users", params={"query": "managed"})
    managed = users.json()[0]
    updated = await client.patch(
        f"/api/v1/admin/users/{managed['id']}",
        json={"status": "suspended", "role": "support"},
    )
    assert updated.json()["status"] == "suspended"
    assert (
        await client.post(f"/api/v1/admin/users/{managed['id']}/sessions/revoke")
    ).status_code == 200
    assert (await client.post(f"/api/v1/admin/users/{managed['id']}/mfa/reset")).status_code == 200

    created = await client.post(
        "/api/v1/admin/oauth-clients",
        json={
            "name": "Update Client",
            "redirect_uris": ["https://update.example/callback"],
            "allowed_scopes": ["openid"],
            "is_public": True,
            "token_endpoint_auth_method": "none",
        },
    )
    client_id = created.json()["client_id"]
    patched = await client.patch(
        f"/api/v1/admin/oauth-clients/{client_id}",
        json={"name": "Updated Client", "enabled": False},
    )
    assert patched.json()["enabled"] is False
    assert (await client.get("/api/v1/admin/oauth-clients")).status_code == 200
    assert (await client.get("/api/v1/admin/sessions")).status_code == 200
    assert (
        await client.get("/api/v1/admin/audit", params={"event_type": "OAUTH_CLIENT_UPDATED"})
    ).json()
    assert (await client.get("/api/v1/admin/security-policies")).status_code == 200


async def test_operational_and_development_endpoints(client: AsyncClient) -> None:
    assert (await client.get("/health")).json()["status"] == "ok"
    assert (await client.get("/ready")).json()["status"] == "ready"
    assert (await client.get("/privacy")).json()["status"] == "published"
    await client.post(
        "/api/v1/auth/register",
        json={
            "email": "mailbox@example.com",
            "password": "SecurePassword2026!",
            "name": "Mailbox User",
            "accept_terms": True,
        },
    )
    mailbox = await client.get("/api/v1/dev/mailbox", params={"email": "mailbox@example.com"})
    assert mailbox.status_code == 200
    assert "token=" in mailbox.json()[0]["text"]
    assert get_mail_service().outbox


async def test_local_cache_ttl_increment_and_delete() -> None:
    cache = get_cache()
    cache._redis_available = False
    await cache.set("test:key", "value", 60)
    assert await cache.get("test:key") == "value"
    count, remaining = await cache.increment("test:count", 60)
    assert count == 1
    assert remaining > 0
    count, _ = await cache.increment("test:count", 60)
    assert count == 2
    await cache.delete("test:key")
    assert await cache.get("test:key") is None


async def test_oauth_missing_parameters_and_unsupported_grant(client: AsyncClient) -> None:
    await seed_client()
    _verifier, challenge = pkce()
    base = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": "openid",
        "nonce": "nonce",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    assert (await client.get("/authorize", params=base)).status_code == 400
    assert (
        await client.post(
            "/token",
            data={"grant_type": "unsupported"},
            auth=(CLIENT_ID, CLIENT_SECRET),
        )
    ).status_code == 400
    assert (
        await client.post(
            "/token",
            data={"grant_type": "authorization_code"},
            auth=(CLIENT_ID, CLIENT_SECRET),
        )
    ).status_code == 400
    assert (
        await client.get("/userinfo", headers={"Authorization": "Basic invalid"})
    ).status_code == 401


def test_pkce_vector_is_stable() -> None:
    verifier = "coverage-verifier-" * 4
    challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    )
    assert len(challenge) == 43
