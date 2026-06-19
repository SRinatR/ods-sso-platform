import hashlib
from dataclasses import dataclass

from app.errors import AppError
from app.services.cache import get_cache


@dataclass(frozen=True)
class RateLimit:
    name: str
    requests: int
    window_seconds: int


REGISTRATION_LIMIT = RateLimit("registration", 3, 3600)
LOGIN_LIMIT = RateLimit("login", 5, 60)
MFA_LIMIT = RateLimit("mfa", 3, 60)
OAUTH_AUTHORIZE_LIMIT = RateLimit("oauth_authorize", 30, 60)
OAUTH_TOKEN_LIMIT = RateLimit("oauth_token", 60, 60)
OAUTH_INTROSPECT_LIMIT = RateLimit("oauth_introspect", 120, 60)
ADMIN_LIMIT = RateLimit("admin", 60, 60)


async def enforce_rate_limit(rule: RateLimit, identity: str) -> None:
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()
    key = f"ratelimit:{rule.name}:{digest}"
    count, retry_after = await get_cache().increment(key, rule.window_seconds)
    if count > rule.requests:
        raise AppError(
            429,
            "rate_limit_exceeded",
            "Too many requests",
            details=[{"limit": rule.requests, "window_seconds": rule.window_seconds}],
            headers={"Retry-After": str(retry_after)},
        )
