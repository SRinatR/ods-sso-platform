from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.services.audit import get_user_by_id
from app.services.session import get_current_user_id
from app.tokens.platform_jwt import get_jwt_service

router = APIRouter()


@router.get("/oauth/userinfo")
async def userinfo(request: Request, db: AsyncSession = Depends(get_db)):
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise HTTPException(401, "invalid_token")
    token = auth[7:]
    jwt_svc = get_jwt_service()
    try:
        claims = jwt_svc.decode_token(token)
    except Exception:
        raise HTTPException(401, "invalid_token")

    user = await get_user_by_id(db, claims["sub"])
    if not user:
        raise HTTPException(401, "invalid_token")

    return {
        "sub": user.id,
        "email": user.email,
        "email_verified": user.email_verified,
        "name": user.name,
    }


@router.get("/session/check")
async def session_check(request: Request):
    user_id = await get_current_user_id(request)
    return {"authenticated": bool(user_id), "user_id": user_id}
