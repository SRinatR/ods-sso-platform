import secrets
from typing import Optional

from fastapi import Request, Response
from itsdangerous import BadSignature, URLSafeTimedSerializer

from app.config import settings
from app.oauth.store import get_oauth_store

SESSION_COOKIE = "ods_session"
SERIALIZER = URLSafeTimedSerializer(settings.session_secret, salt="ods-session")


def create_session(response: Response, user_id: str) -> str:
    session_id = secrets.token_urlsafe(32)
    signed = SERIALIZER.dumps(session_id)
    response.set_cookie(
        SESSION_COOKIE,
        signed,
        httponly=True,
        secure=settings.env != "dev",
        samesite="lax",
        max_age=86400,
        path="/",
    )
    return session_id


async def bind_session(session_id: str, user_id: str) -> None:
    store = get_oauth_store()
    await store.save_session(session_id, user_id)


async def get_current_user_id(request: Request) -> Optional[str]:
    signed = request.cookies.get(SESSION_COOKIE)
    if not signed:
        return None
    try:
        session_id = SERIALIZER.loads(signed, max_age=86400)
    except BadSignature:
        return None
    store = get_oauth_store()
    return await store.get_session_user(session_id)


async def clear_session(request: Request, response: Response) -> None:
    signed = request.cookies.get(SESSION_COOKIE)
    if signed:
        try:
            session_id = SERIALIZER.loads(signed, max_age=86400)
            store = get_oauth_store()
            await store.delete_session(session_id)
        except BadSignature:
            pass
    response.delete_cookie(SESSION_COOKIE, path="/")
