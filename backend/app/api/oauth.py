from typing import Annotated
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, Form, Query, Request
from fastapi.responses import RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import get_db
from app.errors import AppError
from app.middleware import client_ip
from app.repositories.oauth import OAuthRepository
from app.schemas import (
    OAuthConsentDetailsResponse,
    OAuthIntrospectionResponse,
    OAuthTokenResponse,
    UserInfoResponse,
)
from app.services.oauth import OAuthService
from app.services.rate_limit import (
    OAUTH_AUTHORIZE_LIMIT,
    OAUTH_INTROSPECT_LIMIT,
    OAUTH_TOKEN_LIMIT,
    enforce_rate_limit,
)
from app.services.session import authenticate_session

router = APIRouter(tags=["OAuth 2.0 and OpenID Connect"])


@router.get("/authorize")
@router.get("/api/v1/oauth/authorize", include_in_schema=False)
async def authorize(
    request: Request,
    client_id: Annotated[str, Query(min_length=1, max_length=96)],
    redirect_uri: Annotated[str, Query(min_length=1, max_length=2048)],
    response_type: str,
    scope: str = "openid",
    state: str | None = None,
    nonce: str | None = None,
    code_challenge: str | None = None,
    code_challenge_method: str | None = None,
    prompt: str | None = None,
    db: AsyncSession = Depends(get_db),
) -> RedirectResponse:
    await enforce_rate_limit(OAUTH_AUTHORIZE_LIMIT, f"{client_id}:{client_ip(request)}")
    service = OAuthService(db)
    client, scopes = await service.validate_authorization_request(
        client_id,
        redirect_uri,
        response_type,
        scope,
        state,
        nonce,
        code_challenge,
        code_challenge_method,
    )
    try:
        user, session = await authenticate_session(db, request)
    except AppError:
        params = urlencode({"return_to": str(request.url)})
        return RedirectResponse(f"{settings.account_url}/login?{params}", status_code=303)
    redirect_or_request_id, consent_required = await service.begin_authorization(
        request,
        user,
        session,
        client,
        redirect_uri,
        scopes,
        state or "",
        nonce or "",
        code_challenge or "",
        prompt,
    )
    await db.commit()
    if consent_required:
        return RedirectResponse(
            f"{settings.account_url}/consent?request_id={redirect_or_request_id}",
            status_code=303,
        )
    return RedirectResponse(redirect_or_request_id, status_code=303)


@router.get(
    "/api/v1/oauth/consent/{request_id}",
    response_model=OAuthConsentDetailsResponse,
)
async def consent_details(
    request_id: str,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> dict[str, object]:
    user, _session = await authenticate_session(db, request)
    oauth_request = await OAuthRepository(db).get_request(request_id)
    if not oauth_request or oauth_request.user_id != user.id or oauth_request.status != "pending":
        raise AppError(404, "authorization_request_not_found", "Authorization request not found")
    client = await OAuthRepository(db).get_client(oauth_request.client_id)
    if not client:
        raise AppError(404, "client_not_found", "OAuth client not found")
    requested = oauth_request.scope.split()
    missing = await OAuthService(db).consents.required_scopes(
        user.id, client.client_id, requested, force=oauth_request.prompt == "consent"
    )
    return {
        "request_id": oauth_request.id,
        "client_id": client.client_id,
        "client_name": client.name,
        "client_description": client.description,
        "requested_scopes": requested,
        "new_scopes": missing,
    }


@router.post("/api/v1/oauth/consent/approve")
async def approve_consent(
    request: Request,
    request_id: str = Form(...),
    db: AsyncSession = Depends(get_db),
) -> RedirectResponse:
    user, _session = await authenticate_session(db, request)
    redirect = await OAuthService(db).approve_authorization(request, user, request_id)
    await db.commit()
    return RedirectResponse(redirect, status_code=303)


@router.post("/api/v1/oauth/consent/deny")
async def deny_consent(
    request: Request,
    request_id: str = Form(...),
    db: AsyncSession = Depends(get_db),
) -> RedirectResponse:
    user, _session = await authenticate_session(db, request)
    redirect = await OAuthService(db).deny_authorization(request, user, request_id)
    await db.commit()
    return RedirectResponse(redirect, status_code=303)


@router.post("/token", response_model=OAuthTokenResponse)
@router.post("/api/v1/oauth/token", include_in_schema=False)
async def token(
    request: Request,
    grant_type: str = Form(...),
    client_id: str | None = Form(None),
    client_secret: str | None = Form(None),
    code: str | None = Form(None),
    redirect_uri: str | None = Form(None),
    code_verifier: str | None = Form(None),
    refresh_token: str | None = Form(None),
    db: AsyncSession = Depends(get_db),
) -> dict[str, object]:
    await enforce_rate_limit(OAUTH_TOKEN_LIMIT, f"{client_id or 'basic'}:{client_ip(request)}")
    service = OAuthService(db)
    client = await service.authenticate_client(request, client_id, client_secret)
    if grant_type == "authorization_code":
        if not code or not redirect_uri or not code_verifier:
            raise AppError(400, "invalid_request", "Code, redirect_uri and verifier are required")
        result = await service.exchange_code(request, client, code, redirect_uri, code_verifier)
    elif grant_type == "refresh_token":
        if not refresh_token:
            raise AppError(400, "invalid_request", "Refresh token is required")
        result = await service.rotate_refresh_token(request, client, refresh_token)
    else:
        raise AppError(400, "unsupported_grant_type", "Grant type is not supported")
    await db.commit()
    return result


@router.get("/userinfo", response_model=UserInfoResponse)
@router.get("/api/v1/oauth/userinfo", include_in_schema=False)
async def userinfo(
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> dict[str, object]:
    authorization = request.headers.get("authorization", "")
    if not authorization.lower().startswith("bearer "):
        raise AppError(401, "invalid_token", "Bearer access token is required")
    return await OAuthService(db).userinfo(authorization[7:])


@router.post("/revoke", status_code=200)
@router.post("/api/v1/oauth/revoke", status_code=200, include_in_schema=False)
async def revoke(
    request: Request,
    token: str = Form(...),
    client_id: str | None = Form(None),
    client_secret: str | None = Form(None),
    db: AsyncSession = Depends(get_db),
) -> dict[str, bool]:
    service = OAuthService(db)
    client = await service.authenticate_client(request, client_id, client_secret)
    await service.revoke_token(request, client, token)
    await db.commit()
    return {"revoked": True}


@router.post(
    "/introspect",
    response_model=OAuthIntrospectionResponse,
    response_model_exclude_none=True,
)
@router.post("/api/v1/oauth/introspect", include_in_schema=False)
async def introspect(
    request: Request,
    token: str = Form(...),
    client_id: str | None = Form(None),
    client_secret: str | None = Form(None),
    db: AsyncSession = Depends(get_db),
) -> dict[str, object]:
    await enforce_rate_limit(OAUTH_INTROSPECT_LIMIT, f"{client_id or 'basic'}:{client_ip(request)}")
    service = OAuthService(db)
    client = await service.authenticate_client(request, client_id, client_secret)
    return await service.introspect(client, token)
