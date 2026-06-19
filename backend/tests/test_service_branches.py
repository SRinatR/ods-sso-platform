from datetime import timedelta
from typing import Any

import pyotp
import pytest
from fastapi import Request
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import AsyncSessionLocal
from app.db.models import (
    ConsentVersion,
    OAuthClient,
    RefreshToken,
    Session,
    User,
)
from app.errors import AppError
from app.repositories.consents import ConsentRepository
from app.security import (
    get_jwt_service,
    hash_password,
    hash_secret,
    issue_opaque_token,
    utcnow,
)
from app.services.consent import ConsentService
from app.services.identity import IdentityService
from app.services.mfa import MFAService
from app.services.oauth import OAuthService

pytestmark = pytest.mark.integration


def request(headers: list[tuple[bytes, bytes]] | None = None) -> Request:
    item = Request(
        {
            "type": "http",
            "method": "POST",
            "scheme": "http",
            "path": "/test",
            "query_string": b"",
            "headers": headers or [(b"user-agent", b"pytest")],
            "client": ("127.0.0.1", 1234),
            "server": ("testserver", 80),
        }
    )
    item.state.request_id = "req_test"
    return item


async def add_user(
    db: AsyncSession,
    email: str = "branch@example.com",
    *,
    verified: bool = True,
    status: str = "active",
) -> User:
    user = User(
        email=email,
        password_hash=hash_password("SecurePassword2026!"),
        name="Branch User",
        email_verified_at=utcnow() if verified else None,
        status=status,
    )
    db.add(user)
    await db.flush()
    return user


async def add_client(db: AsyncSession, client_id: str = "branch_client") -> OAuthClient:
    client = OAuthClient(
        client_id=client_id,
        client_secret_hash=hash_password("BranchClientSecret2026!"),
        name="Branch Client",
        redirect_uris=["https://branch.example/callback"],
        allowed_scopes=["openid", "email", "profile"],
        grant_types=["authorization_code", "refresh_token"],
        token_endpoint_auth_method="client_secret_basic",
        is_public=False,
        require_pkce=True,
        enabled=True,
    )
    db.add(client)
    await db.flush()
    return client


async def add_session(db: AsyncSession, user: User) -> Session:
    session = Session(
        user_id=user.id,
        secret_hash=hash_secret("session-secret"),
        expires_at=utcnow() + timedelta(hours=1),
        ip_address="127.0.0.1",
    )
    db.add(session)
    await db.flush()
    return session


async def test_identity_service_failure_and_change_password_branches() -> None:
    async with AsyncSessionLocal() as db:
        service = IdentityService(db)
        req = request()
        with pytest.raises(AppError):
            await service.verify_email(req, "evt_missing.invalid-secret-value")
        with pytest.raises(AppError):
            await service.reset_password(
                req, "prt_missing.invalid-secret-value", "NewSecurePassword2026!"
            )
        with pytest.raises(AppError):
            await service.change_password("usr_missing", "NewSecurePassword2026!")

        locked = await add_user(db, "locked@example.com")
        locked.locked_until = utcnow() + timedelta(minutes=5)
        with pytest.raises(AppError) as locked_error:
            await service.verify_primary_credentials(req, locked.email, "SecurePassword2026!")
        assert locked_error.value.error == "account_locked"

        suspended = await add_user(db, "suspended@example.com", status="suspended")
        with pytest.raises(AppError) as unavailable:
            await service.verify_primary_credentials(req, suspended.email, "SecurePassword2026!")
        assert unavailable.value.error == "account_unavailable"

        user = await add_user(db, "change@example.com")
        await service.change_password(user.id, "ChangedPassword2026!")
        await db.flush()
        assert user.password_hash.startswith("$argon2id$")


async def test_identity_missing_user_after_valid_account_tokens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def missing_user(_user_id: str) -> None:
        return None

    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        service = IdentityService(db)
        raw_verification = await service._create_verification_token(user.id)
        monkeypatch.setattr(service.users, "get_by_id", missing_user)
        with pytest.raises(AppError):
            await service.verify_email(request(), raw_verification)

    async with AsyncSessionLocal() as db:
        user = await add_user(db, "reset-branch@example.com")
        service = IdentityService(db)
        token_id, secret, raw_reset = issue_opaque_token("prt")
        await service.tokens.add_reset_token(
            token_id,
            user.id,
            hash_secret(secret),
            utcnow() + timedelta(minutes=10),
        )
        monkeypatch.setattr(service.users, "get_by_id", missing_user)
        with pytest.raises(AppError):
            await service.reset_password(request(), raw_reset, "NewSecurePassword2026!")


async def test_mfa_service_all_validation_branches() -> None:
    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        service = MFAService(db)
        assert await service.verify_totp(user.id, "000000") is False
        assert await service.verify_backup_code(user.id, "missing") is False
        with pytest.raises(AppError):
            await service.enable_totp(user, "000000")

        secret, _uri = await service.setup_totp(user)
        await service.enable_totp(user, pyotp.TOTP(secret).now())
        assert await service.verify_totp(user.id, pyotp.TOTP(secret).now())
        assert not await service.verify_totp(user.id, "000000")

        secret2, _uri2 = await service.setup_totp(user)
        await service.enable_totp(user, pyotp.TOTP(secret2).now())

        wrong_purpose, _ = get_jwt_service().issue(
            user.id,
            "ods-account",
            "preauth",
            60,
            {"purpose": "other"},
        )
        with pytest.raises(AppError):
            await service.verify_challenge(wrong_purpose, "000000", "totp")

        no_mfa = await add_user(db, "no-mfa@example.com")
        challenge, _ = get_jwt_service().issue(
            no_mfa.id,
            "ods-account",
            "preauth",
            60,
            {"purpose": "login_mfa"},
        )
        with pytest.raises(AppError):
            await service.verify_challenge(challenge, "000000", "totp")
        with pytest.raises(AppError):
            await service.verify_step_up(no_mfa, "000000")
        with pytest.raises(AppError):
            await service.verify_step_up(user, None)


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({"client_id": "missing"}, "invalid_client"),
        ({"redirect_uri": "https://evil.example/callback"}, "invalid_redirect_uri"),
        ({"response_type": "token"}, "unsupported_response_type"),
        ({"state": None}, "invalid_request"),
        ({"nonce": None}, "invalid_request"),
        ({"code_challenge_method": "plain"}, "invalid_request"),
        ({"code_challenge": "short"}, "invalid_request"),
        ({"scope": "openid offline_access"}, "invalid_scope"),
    ],
)
async def test_oauth_authorization_validation_branches(
    overrides: dict[str, str | None], expected: str
) -> None:
    async with AsyncSessionLocal() as db:
        await add_client(db)
        values: dict[str, Any] = {
            "client_id": "branch_client",
            "redirect_uri": "https://branch.example/callback",
            "response_type": "code",
            "scope": "openid email",
            "state": "state",
            "nonce": "nonce",
            "code_challenge": "a" * 43,
            "code_challenge_method": "S256",
        }
        values.update(overrides)
        with pytest.raises(AppError) as error:
            await OAuthService(db).validate_authorization_request(**values)
        assert error.value.error == expected


async def test_oauth_client_auth_and_request_failure_branches() -> None:
    async with AsyncSessionLocal() as db:
        client = await add_client(db)
        service = OAuthService(db)
        with pytest.raises(AppError):
            await service.authenticate_client(request(), None, None)
        with pytest.raises(AppError):
            await service.authenticate_client(request(), "missing", "secret")
        client.enabled = False
        with pytest.raises(AppError):
            await service.authenticate_client(
                request(), client.client_id, "BranchClientSecret2026!"
            )
        client.enabled = True
        with pytest.raises(AppError):
            await service.authenticate_client(request(), client.client_id, None)
        with pytest.raises(AppError):
            await service.authenticate_client(request(), client.client_id, "wrong")
        malformed = request([(b"authorization", b"Basic !!!")])
        with pytest.raises(AppError):
            await service.authenticate_client(malformed, None, None)
        no_separator = request(
            [(b"authorization", b"Basic " + __import__("base64").b64encode(b"clientonly"))]
        )
        with pytest.raises(AppError):
            await service.authenticate_client(no_separator, None, None)

        user = await add_user(db)
        with pytest.raises(AppError):
            await service.approve_authorization(request(), user, "missing")
        with pytest.raises(AppError):
            await service.deny_authorization(request(), user, "missing")


async def test_oauth_code_refresh_revoke_and_introspection_failure_branches() -> None:
    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        client = await add_client(db)
        session = await add_session(db, user)
        service = OAuthService(db)
        req = request()
        with pytest.raises(AppError):
            await service.exchange_code(
                req,
                client,
                "cod_missing.invalid-secret-value",
                client.redirect_uris[0],
                "v" * 64,
            )
        with pytest.raises(AppError):
            await service.rotate_refresh_token(req, client, "rft_missing.invalid-secret-value")

        token_id, secret, raw = issue_opaque_token("rft")
        expired = RefreshToken(
            id=token_id,
            secret_hash=hash_secret(secret),
            family_id="fam_expired",
            user_id=user.id,
            client_id=client.client_id,
            session_id=session.id,
            scope="openid",
            expires_at=utcnow() - timedelta(seconds=1),
        )
        db.add(expired)
        await db.flush()
        with pytest.raises(AppError) as error:
            await service.rotate_refresh_token(req, client, raw)
        assert error.value.error == "invalid_grant"

        await service.revoke_token(req, client, "rft_malformed")
        await service.revoke_token(req, client, "not-a-jwt")
        assert await service.introspect(client, "rft_malformed") == {"active": False}
        assert await service.introspect(client, "not-a-jwt") == {"active": False}
        with pytest.raises(AppError):
            await service.userinfo("not-a-jwt")


async def test_consent_version_scope_grant_and_revoke_branches() -> None:
    async with AsyncSessionLocal() as db:
        await db.execute(delete(ConsentVersion))
        user = await add_user(db)
        client = await add_client(db)
        service = ConsentService(db)
        version = await service.ensure_active_version()
        assert version.version == "1.0"
        assert (await service.ensure_active_version()).id == version.id
        assert await service.required_scopes(user.id, client.client_id, ["openid"], force=True) == [
            "openid"
        ]
        assert await service.required_scopes(user.id, client.client_id, ["openid"]) == ["openid"]
        consent = await service.grant(request(), user, client.client_id, ["openid", "email"])
        assert await service.required_scopes(user.id, client.client_id, ["openid", "email"]) == []
        updated = await service.grant(request(), user, client.client_id, ["profile"])
        assert set(updated.scopes) == {"openid", "email", "profile"}
        with pytest.raises(AppError):
            await service.revoke(request(), user, "missing")
        await service.revoke(request(), user, consent.id)
        with pytest.raises(AppError):
            await service.revoke(request(), user, consent.id)
        assert await ConsentRepository(db).active_version("missing") is None
