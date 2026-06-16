import hashlib
import json
import secrets
from dataclasses import dataclass
from typing import Optional

import redis.asyncio as redis

from app.config import settings


@dataclass
class AuthCodeData:
    user_id: str
    client_id: str
    redirect_uri: str
    scope: str
    code_challenge: str
    code_challenge_method: str
    nonce: Optional[str] = None


class OAuthStore:
    def __init__(self, redis_url: str):
        self.redis = redis.from_url(redis_url, decode_responses=True)

    async def save_auth_code(self, data: AuthCodeData) -> str:
        code = secrets.token_urlsafe(32)
        key = f"oauth:code:{code}"
        await self.redis.setex(key, settings.auth_code_ttl, json.dumps(data.__dict__))
        return code

    async def consume_auth_code(self, code: str) -> Optional[AuthCodeData]:
        key = f"oauth:code:{code}"
        raw = await self.redis.get(key)
        if not raw:
            return None
        await self.redis.delete(key)
        d = json.loads(raw)
        return AuthCodeData(**d)

    async def save_session(self, session_id: str, user_id: str, ttl: int = 86400) -> None:
        await self.redis.setex(f"session:{session_id}", ttl, user_id)

    async def get_session_user(self, session_id: str) -> Optional[str]:
        return await self.redis.get(f"session:{session_id}")

    async def delete_session(self, session_id: str) -> None:
        await self.redis.delete(f"session:{session_id}")


def verify_pkce(code_verifier: str, code_challenge: str, method: str) -> bool:
    if method == "S256":
        digest = hashlib.sha256(code_verifier.encode()).digest()
        import base64

        computed = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
        return computed == code_challenge
    if method == "plain":
        return code_verifier == code_challenge
    return False


_store: Optional[OAuthStore] = None


def get_oauth_store() -> OAuthStore:
    global _store
    if _store is None:
        _store = OAuthStore(settings.redis_url)
    return _store
