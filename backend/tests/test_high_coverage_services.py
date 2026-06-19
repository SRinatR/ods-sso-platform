from datetime import timedelta
from email.message import EmailMessage
from urllib.parse import parse_qs, urlparse

import pyotp
import pytest
from fastapi import Request, Response

from app.config import settings
from app.db.database import AsyncSessionLocal
from app.db.models import OAuthAccessToken, OAuthAuthorizationRequest, OAuthClient, RefreshToken
from app.errors import AppError
from app.repositories.sessions import SessionRepository
from app.security import hash_secret, issue_opaque_token, utcnow
from app.services.consent import ConsentService
from app.services.identity import IdentityService
from app.services.mail import MailService
from app.services.mfa import MFAService
from app.services.oauth import OAuthService
from app.services.session import (
    SESSION_COOKIE,
    authenticate_session,
    create_session,
    revoke_all_sessions,
    revoke_session,
)

from .test_oauth_flow import pkce
from .test_service_branches import add_client, add_session, add_user, request

pytestmark = pytest.mark.integration


def cookie_request(value: str | None) -> Request:
    headers = [(b"user-agent", b"pytest")]
    if value is not None:
        headers.append((b"cookie", f"{SESSION_COOKIE}={value}".encode()))
    return request(headers)


async def test_identity_direct_success_and_duplicate_branches() -> None:
    async with AsyncSessionLocal() as db:
        service = IdentityService(db)
        req = request()
        await service.register(
            req,
            "direct@example.com",
            "SecurePassword2026!",
            "Direct User",
            True,
        )
        with pytest.raises(AppError):
            await service.register(
                req,
                "direct@example.com",
                "SecurePassword2026!",
                "Direct User",
                True,
            )
        await service.resend_verification(req, "direct@example.com")
        verification_mail = service.tokens
        user = await service.users.get_by_email("direct@example.com")
        assert user
        raw_verification = await service._create_verification_token(user.id)
        await service.verify_email(req, raw_verification)
        await service.resend_verification(req, "direct@example.com")
        await service.forgot_password(req, "direct@example.com")

        verified = await service.verify_primary_credentials(
            req, "direct@example.com", "SecurePassword2026!"
        )
        response = Response()
        session = await service.complete_login(req, response, verified, mfa_completed=False)
        assert session.user_id == verified.id

        with pytest.raises(AppError):
            await service.verify_primary_credentials(req, "direct@example.com", "wrong-password")
        assert verification_mail


async def test_mfa_backup_match_and_invalid_challenge_code() -> None:
    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        service = MFAService(db)
        secret, _ = await service.setup_totp(user)
        codes = await service.enable_totp(user, pyotp.TOTP(secret).now())
        assert await service.verify_backup_code(user.id, codes[0])
        assert not await service.verify_backup_code(user.id, codes[0])
        challenge = IdentityService(db).issue_mfa_challenge(user.id)
        with pytest.raises(AppError):
            await service.verify_challenge(challenge, "000000", "totp")


async def test_oauth_direct_happy_path_and_inactive_failures() -> None:
    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        oauth_client = await add_client(db)
        session = await add_session(db, user)
        service = OAuthService(db)
        req = request()
        verifier, challenge = pkce()
        validated_client, scopes = await service.validate_authorization_request(
            oauth_client.client_id,
            oauth_client.redirect_uris[0],
            "code",
            "openid email profile",
            "state",
            "nonce",
            challenge,
            "S256",
        )
        request_id, needs_consent = await service.begin_authorization(
            req,
            user,
            session,
            validated_client,
            oauth_client.redirect_uris[0],
            scopes,
            "state",
            "nonce",
            challenge,
            None,
        )
        assert needs_consent
        callback = await service.approve_authorization(req, user, request_id)
        raw_code = parse_qs(urlparse(callback).query)["code"][0]
        with pytest.raises(AppError):
            await service.exchange_code(
                req,
                oauth_client,
                raw_code,
                oauth_client.redirect_uris[0],
                "wrong-verifier",
            )

        authorization_request = await service.repo.get_request(request_id)
        assert authorization_request
        second_id, second_needs_consent = await service.begin_authorization(
            req,
            user,
            session,
            oauth_client,
            oauth_client.redirect_uris[0],
            scopes,
            "state2",
            "nonce2",
            challenge,
            None,
        )
        assert second_needs_consent is False
        second_code = parse_qs(urlparse(second_id).query)["code"][0]
        session.revoked_at = utcnow()
        with pytest.raises(AppError):
            await service.exchange_code(
                req,
                oauth_client,
                second_code,
                oauth_client.redirect_uris[0],
                verifier,
            )


async def test_oauth_refresh_and_introspection_state_branches() -> None:
    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        oauth_client = await add_client(db)
        session = await add_session(db, user)
        service = OAuthService(db)
        req = request()
        token_id, secret, raw = issue_opaque_token("rft")
        refresh = RefreshToken(
            id=token_id,
            secret_hash=hash_secret(secret),
            family_id="fam_branch",
            user_id=user.id,
            client_id=oauth_client.client_id,
            session_id=session.id,
            scope="openid",
            expires_at=utcnow() + timedelta(minutes=5),
        )
        db.add(refresh)
        await db.flush()
        wrong_raw = f"{token_id}.wrong-secret"
        with pytest.raises(AppError):
            await service.rotate_refresh_token(req, oauth_client, wrong_raw)
        session.revoked_at = utcnow()
        with pytest.raises(AppError):
            await service.rotate_refresh_token(req, oauth_client, raw)
        session.revoked_at = None

        await service.revoke_token(req, oauth_client, "rft_unknown_identifier.unknown-secret")
        assert await service.introspect(oauth_client, "rft_unknown_identifier.unknown-secret") == {
            "active": False
        }

        from app.security import get_jwt_service

        encoded, claims = get_jwt_service().issue(
            user.id,
            oauth_client.client_id,
            "access",
            60,
            {"scope": "openid"},
        )
        db.add(
            OAuthAccessToken(
                jti=claims["jti"],
                user_id=user.id,
                client_id=oauth_client.client_id,
                session_id=session.id,
                scope="openid",
                issued_at=utcnow(),
                expires_at=utcnow() + timedelta(minutes=1),
                revoked_at=utcnow(),
            )
        )
        await db.flush()
        assert await service.introspect(oauth_client, encoded) == {"active": False}
        with pytest.raises(AppError):
            await service.userinfo(encoded)


async def test_session_service_authentication_and_revocation_branches() -> None:
    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        response = Response()
        session = await create_session(
            db,
            response,
            user,
            "127.0.0.1",
            "pytest",
            mfa_completed=False,
        )
        raw_cookie = response.headers["set-cookie"].split(";", 1)[0].split("=", 1)[1]
        authenticated_user, authenticated_session = await authenticate_session(
            db, cookie_request(raw_cookie)
        )
        assert authenticated_user.id == user.id
        assert authenticated_session.id == session.id

        with pytest.raises(AppError):
            await authenticate_session(db, cookie_request(None))
        with pytest.raises(AppError):
            await authenticate_session(db, cookie_request("malformed"))
        with pytest.raises(AppError):
            await authenticate_session(db, cookie_request("ses_missing.invalid-secret-value"))

        user.status = "suspended"
        with pytest.raises(AppError):
            await authenticate_session(db, cookie_request(raw_cookie))
        user.status = "active"

        other_response = Response()
        other = await create_session(
            db,
            other_response,
            user,
            "127.0.0.2",
            "pytest-2",
            mfa_completed=True,
        )
        assert await revoke_session(
            db,
            Response(),
            user.id,
            other.id,
            clear_cookie=False,
        )
        assert not await revoke_session(
            db,
            Response(),
            user.id,
            "ses_missing",
            clear_cookie=False,
        )
        assert await revoke_all_sessions(db, user.id, except_id=session.id) == 0
        assert await revoke_all_sessions(db, user.id) == 1
        assert await SessionRepository(db).get(session.id)


async def test_mail_smtp_path_and_consent_force_branch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mail = MailService()
    calls = 0

    def record_send(_message: EmailMessage) -> None:
        nonlocal calls
        calls += 1

    original_env = settings.env
    original_host = settings.smtp_host
    settings.env = "staging"
    settings.smtp_host = "smtp.example.com"
    try:
        monkeypatch.setattr(mail, "_send_sync", record_send)
        await mail.send("recipient@example.com", "Subject", "Body")
        assert calls == 1
    finally:
        settings.env = original_env
        settings.smtp_host = original_host

    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        oauth_client = await add_client(db)
        consent = ConsentService(db)
        assert await consent.required_scopes(
            user.id, oauth_client.client_id, ["openid"], force=True
        ) == ["openid"]


async def test_oauth_additional_success_branches() -> None:
    async with AsyncSessionLocal() as db:
        user = await add_user(db)
        oauth_client = await add_client(db)
        session = await add_session(db, user)
        public = OAuthClient(
            client_id="direct_public",
            name="Direct Public",
            redirect_uris=["http://127.0.0.1/callback"],
            allowed_scopes=["openid"],
            grant_types=["authorization_code", "refresh_token"],
            token_endpoint_auth_method="none",
            is_public=True,
            require_pkce=True,
            enabled=True,
        )
        db.add(public)
        pending = OAuthAuthorizationRequest(
            user_id=user.id,
            session_id=session.id,
            client_id=oauth_client.client_id,
            redirect_uri=oauth_client.redirect_uris[0],
            scope="openid",
            state=None,
            nonce="nonce",
            code_challenge="a" * 43,
            status="pending",
            expires_at=utcnow() + timedelta(minutes=5),
        )
        db.add(pending)
        await db.flush()
        service = OAuthService(db)
        denied = await service.deny_authorization(request(), user, pending.id)
        assert "error=access_denied" in denied
        assert (
            await service.authenticate_client(request(), public.client_id, None)
        ).client_id == public.client_id
        assert (
            await service.authenticate_client(
                request(), oauth_client.client_id, "BranchClientSecret2026!"
            )
        ).client_id == oauth_client.client_id

        await ConsentService(db).grant(
            request(),
            user,
            oauth_client.client_id,
            ["openid", "email", "profile"],
        )
        from app.security import get_jwt_service

        encoded, claims = get_jwt_service().issue(
            user.id,
            oauth_client.client_id,
            "access",
            60,
            {"scope": "openid email profile"},
        )
        db.add(
            OAuthAccessToken(
                jti=claims["jti"],
                user_id=user.id,
                client_id=oauth_client.client_id,
                session_id=session.id,
                scope="openid email profile",
                issued_at=utcnow(),
                expires_at=utcnow() + timedelta(minutes=1),
            )
        )
        await db.flush()
        info = await service.userinfo(encoded)
        assert info["email"] == user.email
        assert info["name"] == user.name
        assert (await service.introspect(oauth_client, encoded))["active"] is True
