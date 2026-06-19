from datetime import timedelta

from fastapi import Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.models import Session, User
from app.errors import AppError
from app.middleware import client_ip
from app.repositories.tokens import AccountTokenRepository
from app.repositories.users import UserRepository
from app.security import (
    as_utc,
    constant_time_secret_matches,
    get_jwt_service,
    hash_password,
    hash_secret,
    issue_opaque_token,
    split_opaque_token,
    utcnow,
    verify_password,
)
from app.services.audit import write_audit_log
from app.services.mail import get_mail_service
from app.services.session import create_session, revoke_all_sessions

MAX_FAILED_LOGINS = 5
ACCOUNT_LOCK_SECONDS = 900


class IdentityService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db
        self.users = UserRepository(db)
        self.tokens = AccountTokenRepository(db)

    async def register(
        self,
        request: Request,
        email: str,
        password: str,
        name: str,
        accept_terms: bool,
    ) -> None:
        if not accept_terms:
            raise AppError(422, "terms_required", "Terms must be accepted")
        if await self.users.get_by_email(email):
            raise AppError(409, "email_already_registered", "Email is already registered")
        user = await self.users.create(email, hash_password(password), name)
        raw_token = await self._create_verification_token(user.id)
        await write_audit_log(
            self.db,
            request,
            "USER_REGISTERED",
            actor_id=user.id,
            subject_id=user.id,
        )
        await get_mail_service().send_verification(user.email, raw_token)
        await write_audit_log(
            self.db,
            request,
            "EMAIL_VERIFICATION_SENT",
            actor_id=user.id,
            subject_id=user.id,
        )

    async def _create_verification_token(self, user_id: str) -> str:
        token_id, secret, raw = issue_opaque_token("evt")
        await self.tokens.add_email_token(
            token_id,
            user_id,
            hash_secret(secret),
            utcnow() + timedelta(seconds=settings.verification_token_ttl),
        )
        return raw

    async def verify_email(self, request: Request, raw_token: str) -> None:
        token_id, secret = split_opaque_token(raw_token, "evt")
        token = await self.tokens.get_email_token(token_id)
        now = utcnow()
        if (
            not token
            or token.used_at is not None
            or as_utc(token.expires_at) <= now
            or not constant_time_secret_matches(secret, token.secret_hash)
        ):
            raise AppError(400, "invalid_verification_token", "Verification token is invalid")
        user = await self.users.get_by_id(token.user_id)
        if not user:
            raise AppError(400, "invalid_verification_token", "Verification token is invalid")
        token.used_at = now
        await self.users.mark_verified(user, now)
        await write_audit_log(
            self.db,
            request,
            "EMAIL_VERIFIED",
            actor_id=user.id,
            subject_id=user.id,
        )

    async def resend_verification(self, request: Request, email: str) -> None:
        user = await self.users.get_by_email(email)
        if user and not user.email_verified:
            raw_token = await self._create_verification_token(user.id)
            await get_mail_service().send_verification(user.email, raw_token)
            await write_audit_log(
                self.db,
                request,
                "EMAIL_VERIFICATION_SENT",
                actor_id=user.id,
                subject_id=user.id,
            )

    async def forgot_password(self, request: Request, email: str) -> None:
        user = await self.users.get_by_email(email)
        if not user:
            return
        token_id, secret, raw = issue_opaque_token("prt")
        await self.tokens.add_reset_token(
            token_id,
            user.id,
            hash_secret(secret),
            utcnow() + timedelta(seconds=settings.password_reset_token_ttl),
        )
        await get_mail_service().send_password_reset(user.email, raw)
        await write_audit_log(
            self.db,
            request,
            "PASSWORD_RESET_REQUESTED",
            actor_id=user.id,
            subject_id=user.id,
        )

    async def reset_password(self, request: Request, raw_token: str, new_password: str) -> None:
        token_id, secret = split_opaque_token(raw_token, "prt")
        token = await self.tokens.get_reset_token(token_id)
        now = utcnow()
        if (
            not token
            or token.used_at is not None
            or as_utc(token.expires_at) <= now
            or not constant_time_secret_matches(secret, token.secret_hash)
        ):
            raise AppError(400, "invalid_reset_token", "Password reset token is invalid")
        user = await self.users.get_by_id(token.user_id)
        if not user:
            raise AppError(400, "invalid_reset_token", "Password reset token is invalid")
        user.password_hash = hash_password(new_password)
        token.used_at = now
        await self.tokens.invalidate_reset_tokens(user.id, now)
        await revoke_all_sessions(self.db, user.id)
        await write_audit_log(
            self.db,
            request,
            "PASSWORD_RESET_COMPLETED",
            actor_id=user.id,
            subject_id=user.id,
        )

    async def verify_primary_credentials(
        self,
        request: Request,
        email: str,
        password: str,
    ) -> User:
        user = await self.users.get_by_email(email)
        now = utcnow()
        if user and user.locked_until and as_utc(user.locked_until) > now:
            await self.users.record_login(
                email,
                False,
                client_ip(request),
                request.headers.get("user-agent"),
                user.id,
                "account_locked",
            )
            await write_audit_log(
                self.db,
                request,
                "LOGIN_FAILED",
                actor_id=user.id,
                subject_id=user.id,
                details={"reason": "account_locked"},
            )
            await self.db.commit()
            raise AppError(423, "account_locked", "Account is temporarily locked")
        if user is None or not verify_password(password, user.password_hash):
            if user:
                user.failed_login_count += 1
                if user.failed_login_count >= MAX_FAILED_LOGINS:
                    user.locked_until = now + timedelta(seconds=ACCOUNT_LOCK_SECONDS)
            await self.users.record_login(
                email,
                False,
                client_ip(request),
                request.headers.get("user-agent"),
                user.id if user else None,
                "invalid_credentials",
            )
            await write_audit_log(
                self.db,
                request,
                "LOGIN_FAILED",
                actor_id=user.id if user else None,
                subject_id=user.id if user else None,
                details={"reason": "invalid_credentials"},
            )
            await self.db.commit()
            raise AppError(401, "invalid_credentials", "Email or password is incorrect")
        if user.status != "active":
            raise AppError(403, "account_unavailable", "Account is not active")
        if not user.email_verified:
            raise AppError(403, "email_not_verified", "Email verification is required")
        user.failed_login_count = 0
        user.locked_until = None
        return user

    async def complete_login(
        self,
        request: Request,
        response: Response,
        user: User,
        *,
        mfa_completed: bool,
    ) -> Session:
        now = utcnow()
        session = await create_session(
            self.db,
            response,
            user,
            client_ip(request),
            request.headers.get("user-agent"),
            mfa_completed=mfa_completed,
        )
        user.last_login_at = now
        await self.users.record_login(
            user.email,
            True,
            client_ip(request),
            request.headers.get("user-agent"),
            user.id,
        )
        await write_audit_log(
            self.db,
            request,
            "LOGIN_SUCCESS",
            actor_id=user.id,
            subject_id=user.id,
            details={"mfa": mfa_completed},
        )
        return session

    def issue_mfa_challenge(self, user_id: str) -> str:
        token, _claims = get_jwt_service().issue(
            user_id,
            "ods-account",
            "preauth",
            settings.preauth_token_ttl,
            {"purpose": "login_mfa"},
        )
        return token

    async def change_password(self, user_id: str, password: str) -> None:
        user = await self.users.get_by_id(user_id)
        if not user:
            raise AppError(404, "user_not_found", "User was not found")
        user.password_hash = hash_password(password)
        await revoke_all_sessions(self.db, user.id)
