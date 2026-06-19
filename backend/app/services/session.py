from datetime import timedelta

from fastapi import Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.models import Session, User
from app.errors import AppError
from app.repositories.oauth import OAuthRepository
from app.repositories.sessions import SessionRepository
from app.repositories.users import UserRepository
from app.security import (
    as_utc,
    constant_time_secret_matches,
    hash_secret,
    issue_opaque_token,
    split_opaque_token,
    utcnow,
)

SESSION_COOKIE = "ods_session"


def set_session_cookie(response: Response, raw_token: str) -> None:
    response.set_cookie(
        SESSION_COOKIE,
        raw_token,
        httponly=True,
        secure=settings.secure_cookies,
        samesite="lax",
        max_age=settings.session_ttl,
        path="/",
    )


def clear_session_cookie(response: Response) -> None:
    response.delete_cookie(
        SESSION_COOKIE,
        path="/",
        secure=settings.secure_cookies,
        httponly=True,
        samesite="lax",
    )


async def create_session(
    db: AsyncSession,
    response: Response,
    user: User,
    ip_address: str,
    user_agent: str | None,
    *,
    mfa_completed: bool,
) -> Session:
    session_id, secret, raw = issue_opaque_token("ses")
    now = utcnow()
    session = await SessionRepository(db).create(
        session_id=session_id,
        user_id=user.id,
        secret_hash=hash_secret(secret),
        ip_address=ip_address,
        user_agent=user_agent,
        expires_at=now + timedelta(seconds=settings.session_ttl),
        mfa_completed_at=now if mfa_completed else None,
    )
    set_session_cookie(response, raw)
    return session


async def authenticate_session(db: AsyncSession, request: Request) -> tuple[User, Session]:
    raw = request.cookies.get(SESSION_COOKIE)
    if not raw:
        raise AppError(401, "not_authenticated", "Authentication is required")
    try:
        session_id, secret = split_opaque_token(raw, "ses")
    except AppError as exc:
        raise AppError(401, "not_authenticated", "Authentication is required") from exc
    session = await SessionRepository(db).get(session_id)
    now = utcnow()
    if (
        not session
        or session.revoked_at is not None
        or as_utc(session.expires_at) <= now
        or not constant_time_secret_matches(secret, session.secret_hash)
    ):
        raise AppError(401, "not_authenticated", "Authentication is required")
    user = await UserRepository(db).get_by_id(session.user_id)
    if not user or user.status != "active":
        raise AppError(401, "account_unavailable", "Account is not active")
    session.last_seen_at = now
    return user, session


async def revoke_session(
    db: AsyncSession,
    response: Response,
    user_id: str,
    session_id: str,
    *,
    clear_cookie: bool = True,
) -> bool:
    now = utcnow()
    revoked = await SessionRepository(db).revoke(session_id, user_id, now)
    if revoked:
        await OAuthRepository(db).revoke_session_tokens(session_id, now)
    if clear_cookie:
        clear_session_cookie(response)
    return revoked


async def revoke_all_sessions(db: AsyncSession, user_id: str, except_id: str | None = None) -> int:
    now = utcnow()
    sessions = await SessionRepository(db).list_active(user_id, now)
    count = await SessionRepository(db).revoke_all(user_id, now, except_id=except_id)
    oauth = OAuthRepository(db)
    for session in sessions:
        if session.id != except_id:
            await oauth.revoke_session_tokens(session.id, now)
    return count
