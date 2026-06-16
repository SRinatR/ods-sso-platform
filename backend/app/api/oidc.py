from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import get_db
from app.services.audit import get_user_by_id
from app.tokens.platform_jwt import get_jwt_service

router = APIRouter()


@router.get("/.well-known/openid-configuration")
async def openid_configuration():
    base = settings.issuer.rstrip("/")
    return {
        "issuer": base,
        "authorization_endpoint": f"{base}/api/v1/auth/oauth/authorize",
        "token_endpoint": f"{base}/api/v1/auth/oauth/token",
        "userinfo_endpoint": f"{base}/api/v1/auth/oauth/userinfo",
        "jwks_uri": f"{base}/.well-known/jwks.json",
        "response_types_supported": ["code"],
        "grant_types_supported": ["authorization_code", "refresh_token"],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "token_endpoint_auth_methods_supported": ["client_secret_post"],
        "code_challenge_methods_supported": ["S256", "plain"],
        "scopes_supported": ["openid", "email", "profile"],
    }


@router.get("/.well-known/jwks.json")
async def jwks():
    return get_jwt_service().jwks()


@router.post("/api/v1/oauth/introspect")
async def introspect(token: str, db: AsyncSession = Depends(get_db)):
    jwt_svc = get_jwt_service()
    try:
        claims = jwt_svc.decode_token(token)
    except Exception:
        return {"active": False}

    user = await get_user_by_id(db, claims.get("sub", ""))
    if not user:
        return {"active": False}

    return {
        "active": True,
        "sub": user.id,
        "email": user.email,
        "client_id": claims.get("aud") or claims.get("client_id"),
        "exp": claims.get("exp"),
        "iss": claims.get("iss"),
    }
