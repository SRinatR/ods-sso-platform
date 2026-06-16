from pydantic import BaseModel, EmailStr, Field

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.db.models import User
from app.identity.keycloak_provider import get_identity_provider
from app.services.audit import get_user_by_email, log_audit
from app.services.session import bind_session, clear_session, create_session, get_current_user_id

router = APIRouter()


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8)


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8)
    name: str | None = None
    accept_terms: bool = True


@router.post("/login")
async def login(
    body: LoginRequest,
    request: Request,
    response: Response,
    db: AsyncSession = Depends(get_db),
):
    provider = get_identity_provider()
    identity = await provider.authenticate(body.email, body.password)
    if not identity:
        await log_audit(
            db,
            "LOGIN_FAILED",
            ip=request.client.host if request.client else None,
            user_agent=request.headers.get("user-agent"),
            metadata={"email": body.email},
        )
        raise HTTPException(401, "invalid_credentials")

    user = await get_user_by_email(db, body.email)
    if not user:
        user = User(
            email=identity.email,
            email_verified=identity.email_verified,
            name=identity.name,
            identity_provider_id=identity.subject,
        )
        db.add(user)
        await db.commit()
        await db.refresh(user)
    else:
        user.email_verified = identity.email_verified
        user.name = identity.name or user.name
        user.identity_provider_id = identity.subject
        await db.commit()

    session_id = create_session(response, user.id)
    await bind_session(session_id, user.id)

    await log_audit(
        db,
        "LOGIN_SUCCESS",
        actor_id=user.id,
        ip=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    return {"ok": True, "user_id": user.id, "email": user.email}


@router.post("/register")
async def register(
    body: RegisterRequest,
    request: Request,
    response: Response,
    db: AsyncSession = Depends(get_db),
):
    if not body.accept_terms:
        raise HTTPException(400, "terms_required")

    existing = await get_user_by_email(db, body.email)
    if existing:
        raise HTTPException(409, "email_already_registered")

    provider = get_identity_provider()
    identity = await provider.create_user(body.email, body.password, body.name)

    user = User(
        email=identity.email,
        email_verified=identity.email_verified,
        name=identity.name,
        identity_provider_id=identity.subject,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)

    session_id = create_session(response, user.id)
    await bind_session(session_id, user.id)

    return {"ok": True, "user_id": user.id, "email": user.email}


@router.post("/logout")
async def logout(request: Request, response: Response, db: AsyncSession = Depends(get_db)):
    user_id = await get_current_user_id(request)
    await clear_session(request, response)
    if user_id:
        await log_audit(
            db,
            "TOKEN_REVOKED",
            actor_id=user_id,
            ip=request.client.host if request.client else None,
            user_agent=request.headers.get("user-agent"),
            metadata={"action": "logout"},
        )
    return {"ok": True}


@router.get("/me")
async def me(request: Request, db: AsyncSession = Depends(get_db)):
    user_id = await get_current_user_id(request)
    if not user_id:
        raise HTTPException(401, "not_authenticated")
    from app.services.audit import get_user_by_id

    user = await get_user_by_id(db, user_id)
    if not user:
        raise HTTPException(401, "not_authenticated")
    return {
        "id": user.id,
        "email": user.email,
        "email_verified": user.email_verified,
        "name": user.name,
    }
