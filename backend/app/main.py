import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text

from app.api import admin, auth, consent, mfa, oauth, oidc, session
from app.config import settings
from app.db.database import AsyncSessionLocal, engine
from app.errors import install_error_handlers
from app.middleware import RequestContextMiddleware
from app.schemas import ErrorResponse
from app.security import validate_runtime_security
from app.seed import run_seed
from app.services.cache import get_cache
from app.services.mail import get_mail_service

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("ods")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    validate_runtime_security()
    await run_seed()
    yield
    await get_cache().close()
    await engine.dispose()


app = FastAPI(
    title="ODS SSO Platform",
    version="1.0.0",
    description="Identity Core, MFA and OAuth 2.0/OpenID Connect provider.",
    lifespan=lifespan,
    contact={"name": "ODS Platform Security"},
    license_info={"name": "Proprietary"},
    responses={
        400: {"model": ErrorResponse, "description": "Invalid request"},
        401: {"model": ErrorResponse, "description": "Authentication failed"},
        403: {"model": ErrorResponse, "description": "Access denied"},
        404: {"model": ErrorResponse, "description": "Resource not found"},
        409: {"model": ErrorResponse, "description": "Resource conflict"},
        422: {"model": ErrorResponse, "description": "Validation failed"},
        429: {"model": ErrorResponse, "description": "Rate limit exceeded"},
    },
)
app.state.logger = logger
app.add_middleware(RequestContextMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
)
install_error_handlers(app)

app.include_router(auth.router)
app.include_router(mfa.router)
app.include_router(session.router)
app.include_router(consent.router)
app.include_router(oauth.router)
app.include_router(oidc.router)
app.include_router(admin.router)


@app.get("/health", tags=["Operations"])
async def health() -> dict[str, str]:
    return {"status": "ok", "environment": settings.env}


@app.get("/ready", tags=["Operations"])
async def ready() -> dict[str, str]:
    async with AsyncSessionLocal() as db:
        await db.execute(text("SELECT 1"))
    await get_cache().set("health:ready", "ok", 10)
    return {"status": "ready"}


@app.get("/privacy", tags=["Legal"])
async def privacy() -> dict[str, str]:
    return {
        "title": "Privacy Policy",
        "status": "published",
        "url": f"{settings.account_url}/privacy",
    }


@app.get("/api/v1/dev/mailbox", include_in_schema=False)
async def development_mailbox(email: str) -> list[dict[str, str]]:
    if settings.env not in {"dev", "test"}:
        return []
    return [
        {"recipient": item.recipient, "subject": item.subject, "text": item.text}
        for item in get_mail_service().outbox
        if item.recipient.lower() == email.lower()
    ]
