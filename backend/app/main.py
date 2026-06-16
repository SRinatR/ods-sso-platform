"""ODS SSO Platform — FastAPI application."""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import auth, oauth, oidc, session
from app.config import settings
from app.db.database import engine
from app.db.models import Base
from app.seed import run_seed


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    await run_seed()
    yield
    await engine.dispose()


app = FastAPI(
    title="ODS SSO Platform",
    version="0.1.0-pilot",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.account_url, "http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router, prefix="/api/v1/auth", tags=["auth"])
app.include_router(oauth.router, prefix="/api/v1/auth/oauth", tags=["oauth"])
app.include_router(session.router, prefix="/api/v1/auth", tags=["session"])
app.include_router(oidc.router, tags=["oidc"])


@app.get("/health")
async def health():
    return {"status": "ok", "env": settings.env}


@app.get("/privacy")
async def privacy():
    return {"title": "Privacy Policy", "status": "draft", "url": f"{settings.account_url}/privacy"}
