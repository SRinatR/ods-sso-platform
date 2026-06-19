import asyncio
import time
from functools import lru_cache

import redis.asyncio as redis

from app.config import settings


class CacheService:
    def __init__(self) -> None:
        self.redis = redis.from_url(settings.redis_url, decode_responses=True)
        self._local: dict[str, tuple[str, float]] = {}
        self._lock = asyncio.Lock()
        self._redis_available: bool | None = None

    async def _use_redis(self) -> bool:
        if self._redis_available is not None:
            return self._redis_available
        try:
            await self.redis.ping()
            self._redis_available = True
        except (redis.RedisError, OSError) as exc:
            if settings.env not in {"dev", "test"}:
                raise RuntimeError("Redis is required outside development") from exc
            self._redis_available = False
        return self._redis_available

    async def set(self, key: str, value: str, ttl: int) -> None:
        if await self._use_redis():
            await self.redis.setex(key, ttl, value)
            return
        async with self._lock:
            self._local[key] = (value, time.monotonic() + ttl)

    async def get(self, key: str) -> str | None:
        if await self._use_redis():
            return await self.redis.get(key)
        async with self._lock:
            item = self._local.get(key)
            if not item:
                return None
            value, expires_at = item
            if expires_at <= time.monotonic():
                self._local.pop(key, None)
                return None
            return value

    async def delete(self, key: str) -> None:
        if await self._use_redis():
            await self.redis.delete(key)
            return
        async with self._lock:
            self._local.pop(key, None)

    async def increment(self, key: str, ttl: int) -> tuple[int, int]:
        if await self._use_redis():
            pipe = self.redis.pipeline()
            pipe.incr(key)
            pipe.ttl(key)
            count, remaining = await pipe.execute()
            if count == 1 or remaining < 0:
                await self.redis.expire(key, ttl)
                remaining = ttl
            return int(count), int(remaining)
        async with self._lock:
            now = time.monotonic()
            raw_count, expires_at = self._local.get(key, ("0", now + ttl))
            if expires_at <= now:
                raw_count, expires_at = "0", now + ttl
            count = int(raw_count) + 1
            self._local[key] = (str(count), expires_at)
            return count, max(1, int(expires_at - now))

    async def close(self) -> None:
        await self.redis.aclose()


@lru_cache
def get_cache() -> CacheService:
    return CacheService()
