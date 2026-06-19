import base64
import hashlib
from urllib.parse import parse_qs, urlparse

import pytest
from httpx import AsyncClient

from app.db.database import AsyncSessionLocal
from app.db.models import OAuthClient
from app.security import get_jwt_service, hash_password

from .test_identity_integration import register_and_verify

pytestmark = [pytest.mark.integration, pytest.mark.oauth]

CLIENT_ID = "client_test"
CLIENT_SECRET = "ClientSecret2026!"
REDIRECT_URI = "https://client.example/callback"


def pkce() -> tuple[str, str]:
    verifier = "v" * 64
    challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    )
    return verifier, challenge


async def seed_client() -> None:
    async with AsyncSessionLocal() as db:
        db.add(
            OAuthClient(
                client_id=CLIENT_ID,
                client_secret_hash=hash_password(CLIENT_SECRET),
                name="Test OIDC Client",
                redirect_uris=[REDIRECT_URI],
                allowed_scopes=["openid", "profile", "email", "offline_access"],
                grant_types=["authorization_code", "refresh_token"],
                token_endpoint_auth_method="client_secret_basic",
                is_public=False,
                require_pkce=True,
                enabled=True,
            )
        )
        await db.commit()


async def authorize_and_exchange(client: AsyncClient) -> dict[str, object]:
    verifier, challenge = pkce()
    authorize = await client.get(
        "/authorize",
        params={
            "client_id": CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "response_type": "code",
            "scope": "openid profile email offline_access",
            "state": "state-123",
            "nonce": "nonce-123",
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        },
        follow_redirects=False,
    )
    assert authorize.status_code == 303
    consent_location = authorize.headers["location"]
    request_id = parse_qs(urlparse(consent_location).query)["request_id"][0]
    details = await client.get(f"/api/v1/oauth/consent/{request_id}")
    assert details.status_code == 200
    assert "email" in details.json()["new_scopes"]
    approved = await client.post(
        "/api/v1/oauth/consent/approve",
        data={"request_id": request_id},
        follow_redirects=False,
    )
    assert approved.status_code == 303
    callback = urlparse(approved.headers["location"])
    params = parse_qs(callback.query)
    assert params["state"] == ["state-123"]
    raw_code = params["code"][0]
    token = await client.post(
        "/token",
        data={
            "grant_type": "authorization_code",
            "code": raw_code,
            "redirect_uri": REDIRECT_URI,
            "code_verifier": verifier,
        },
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert token.status_code == 200
    return token.json()


async def test_complete_oidc_refresh_revoke_flow(client: AsyncClient) -> None:
    await seed_client()
    await register_and_verify(client)
    await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    token_set = await authorize_and_exchange(client)
    id_claims = get_jwt_service().decode(
        str(token_set["id_token"]), audience=CLIENT_ID, token_use="id"
    )
    assert id_claims["nonce"] == "nonce-123"
    assert id_claims["email"] == "user@example.com"

    userinfo = await client.get(
        "/userinfo", headers={"Authorization": f"Bearer {token_set['access_token']}"}
    )
    assert userinfo.status_code == 200
    assert userinfo.json()["sub"] == id_claims["sub"]

    introspection = await client.post(
        "/introspect",
        data={"token": token_set["access_token"]},
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert introspection.json()["active"] is True

    old_refresh = str(token_set["refresh_token"])
    rotated = await client.post(
        "/token",
        data={"grant_type": "refresh_token", "refresh_token": old_refresh},
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert rotated.status_code == 200
    assert rotated.json()["refresh_token"] != old_refresh

    reuse = await client.post(
        "/token",
        data={"grant_type": "refresh_token", "refresh_token": old_refresh},
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert reuse.status_code == 400
    assert reuse.json()["error"] == "invalid_grant"
    inactive = await client.post(
        "/introspect",
        data={"token": token_set["access_token"]},
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert inactive.json() == {"active": False}


async def test_consent_reuse_connected_apps_and_revocation(client: AsyncClient) -> None:
    await seed_client()
    await register_and_verify(client)
    await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    token_set = await authorize_and_exchange(client)
    apps = await client.get("/api/v1/account/connected-apps")
    assert apps.status_code == 200
    consent_id = apps.json()[0]["consent_id"]
    revoked = await client.delete(f"/api/v1/account/connected-apps/{consent_id}")
    assert revoked.status_code == 200
    inactive = await client.post(
        "/introspect",
        data={"token": token_set["access_token"]},
        auth=(CLIENT_ID, CLIENT_SECRET),
    )
    assert inactive.json() == {"active": False}
    denied_userinfo = await client.get(
        "/userinfo", headers={"Authorization": f"Bearer {token_set['access_token']}"}
    )
    assert denied_userinfo.status_code == 401


async def test_oauth_security_rejections(client: AsyncClient) -> None:
    await seed_client()
    _verifier, challenge = pkce()
    plain = await client.get(
        "/authorize",
        params={
            "client_id": CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "response_type": "code",
            "scope": "openid",
            "state": "state",
            "nonce": "nonce",
            "code_challenge": challenge,
            "code_challenge_method": "plain",
        },
        follow_redirects=False,
    )
    assert plain.status_code == 400
    mismatch = await client.get(
        "/authorize",
        params={
            "client_id": CLIENT_ID,
            "redirect_uri": "https://evil.example/callback",
            "response_type": "code",
            "scope": "openid",
            "state": "state",
            "nonce": "nonce",
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        },
        follow_redirects=False,
    )
    assert mismatch.status_code == 400
    unauthenticated = await client.post("/introspect", data={"token": "invalid"})
    assert unauthenticated.status_code == 401
