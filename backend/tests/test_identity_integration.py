import re

import pyotp
import pytest
from httpx import AsyncClient

from app.services.mail import get_mail_service

pytestmark = pytest.mark.integration


def token_from_latest_mail() -> str:
    text = get_mail_service().outbox[-1].text
    match = re.search(r"token=([^\s]+)", text)
    assert match
    return match.group(1)


async def register_and_verify(client: AsyncClient, email: str = "user@example.com") -> None:
    response = await client.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": "SecurePassword2026!",
            "name": "Test User",
            "accept_terms": True,
        },
    )
    assert response.status_code == 201
    token = token_from_latest_mail()
    response = await client.post("/api/v1/auth/verify-email", json={"token": token})
    assert response.status_code == 200


async def test_full_registration_login_session_logout_flow(client: AsyncClient) -> None:
    await register_and_verify(client)
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    assert login.status_code == 200
    assert login.json()["mfa_required"] is False
    assert "ods_session" in client.cookies

    me = await client.get("/api/v1/auth/me")
    assert me.status_code == 200
    assert me.json()["email_verified"] is True

    sessions = await client.get("/api/v1/account/sessions")
    assert sessions.status_code == 200
    assert sessions.json()[0]["current"] is True

    history = await client.get("/api/v1/account/login-history")
    assert history.status_code == 200
    assert history.json()[0]["success"] is True

    logout = await client.post("/api/v1/auth/logout")
    assert logout.status_code == 200
    assert (await client.get("/api/v1/auth/me")).status_code == 401


async def test_password_reset_invalidates_existing_session(client: AsyncClient) -> None:
    await register_and_verify(client)
    await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    response = await client.post("/api/v1/auth/forgot-password", json={"email": "user@example.com"})
    assert response.status_code == 200
    token = token_from_latest_mail()
    reset = await client.post(
        "/api/v1/auth/reset-password",
        json={"token": token, "new_password": "NewSecurePassword2026!"},
    )
    assert reset.status_code == 200
    assert (await client.get("/api/v1/auth/me")).status_code == 401
    assert (
        await client.post(
            "/api/v1/auth/login",
            json={"email": "user@example.com", "password": "NewSecurePassword2026!"},
        )
    ).status_code == 200
    assert (
        await client.post(
            "/api/v1/auth/reset-password",
            json={"token": token, "new_password": "AnotherPassword2026!"},
        )
    ).status_code == 400


async def test_mfa_totp_and_backup_code_login(client: AsyncClient) -> None:
    await register_and_verify(client)
    await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    setup = await client.post("/api/v1/auth/mfa/totp/setup")
    assert setup.status_code == 200
    secret = setup.json()["secret"]
    enable = await client.post(
        "/api/v1/auth/mfa/totp/enable",
        json={"code": pyotp.TOTP(secret).now()},
    )
    assert enable.status_code == 200
    backup_code = enable.json()["backup_codes"][0]
    await client.post("/api/v1/auth/logout")

    primary = await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    assert primary.json()["mfa_required"] is True
    verified = await client.post(
        "/api/v1/auth/mfa/verify",
        json={
            "challenge_token": primary.json()["challenge_token"],
            "code": pyotp.TOTP(secret).now(),
            "method": "totp",
        },
    )
    assert verified.status_code == 200
    await client.post("/api/v1/auth/logout")

    primary = await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    backup = await client.post(
        "/api/v1/auth/mfa/verify",
        json={
            "challenge_token": primary.json()["challenge_token"],
            "code": backup_code,
            "method": "backup",
        },
    )
    assert backup.status_code == 200
    await client.post("/api/v1/auth/logout")
    primary = await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    reused = await client.post(
        "/api/v1/auth/mfa/verify",
        json={
            "challenge_token": primary.json()["challenge_token"],
            "code": backup_code,
            "method": "backup",
        },
    )
    assert reused.status_code == 401


async def test_error_schema_and_brute_force_lock(client: AsyncClient) -> None:
    await register_and_verify(client)
    for _ in range(5):
        response = await client.post(
            "/api/v1/auth/login",
            json={"email": "user@example.com", "password": "wrong-password"},
        )
        assert response.status_code == 401
        assert set(response.json()) == {"error", "message", "details", "request_id"}
    locked = await client.post(
        "/api/v1/auth/login",
        json={"email": "user@example.com", "password": "SecurePassword2026!"},
    )
    assert locked.status_code in {423, 429}
