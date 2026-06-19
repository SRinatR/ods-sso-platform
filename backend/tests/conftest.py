import os
from collections.abc import AsyncIterator

os.environ["ENV"] = "test"
os.environ["DATABASE_URL"] = os.getenv(
    "TEST_DATABASE_URL", "sqlite+aiosqlite:///D:/sso-temp/ods-sso-tests.db"
)
os.environ["REDIS_URL"] = os.getenv("TEST_REDIS_URL", "redis://127.0.0.1:6399/15")
os.environ["SESSION_SECRET"] = "test-session-secret-with-more-than-32-characters"
os.environ["TOKEN_PEPPER"] = "test-token-pepper-with-more-than-32-characters"
os.environ["BOOTSTRAP_ADMIN_EMAIL"] = "admin@example.com"
os.environ["BOOTSTRAP_ADMIN_PASSWORD"] = "AdminPassword2026!"

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db.database import engine
from app.db.models import Base
from app.main import app
from app.seed import run_seed
from app.services.cache import get_cache
from app.services.mail import get_mail_service


@pytest_asyncio.fixture(autouse=True)
async def reset_state() -> AsyncIterator[None]:
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
        for table in reversed(Base.metadata.sorted_tables):
            await connection.execute(table.delete())
    cache = get_cache()
    cache._redis_available = None if os.getenv("TEST_REDIS_URL") else False
    cache._local.clear()
    if os.getenv("TEST_REDIS_URL"):
        await cache.redis.flushdb()
    get_mail_service().outbox.clear()
    await run_seed()
    yield


@pytest_asyncio.fixture
async def client() -> AsyncIterator[AsyncClient]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as test_client:
        yield test_client
