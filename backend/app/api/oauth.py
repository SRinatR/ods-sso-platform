import secrets
from typing import Optional
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, Form, HTTPException, Query, Request
from fastapi.responses import RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import get_db
from app.oauth.store import AuthCodeData, get_oauth_store
from app.services.audit import get_oauth_client, get_user_by_id
from app.services.session import get_current_user_id

router = APIRouter()


@router.get("/authorize")
async def authorize(
    request: Request,
    client_id: str = Query(...),
    redirect_uri: str = Query(...),
    response_type: str = Query(...),
    scope: str = Query("openid email profile"),
    state: Optional[str] = Query(None),
    code_challenge: str = Query(...),
    code_challenge_method: str = Query("S256"),
    nonce: Optional[str] = Query(None),
    kc_idp_hint: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    if response_type != "code":
        raise HTTPException(400, "unsupported_response_type")

    client = await get_oauth_client(db, client_id)
    if not client or not client.enabled:
        raise HTTPException(400, "invalid_client")

    allowed_uris = [u.strip() for u in client.redirect_uris.split("\n") if u.strip()]
    if redirect_uri not in allowed_uris:
        raise HTTPException(400, "invalid_redirect_uri")

    if client.require_pkce and code_challenge_method not in ("S256", "plain"):
        raise HTTPException(400, "invalid_code_challenge_method")

    user_id = await get_current_user_id(request)
    if not user_id:
        params = urlencode(
            {
                "return_to": str(request.url),
                **({"kc_idp_hint": kc_idp_hint} if kc_idp_hint else {}),
            }
        )
        return RedirectResponse(f"{settings.account_url}/login?{params}")

    user = await get_user_by_id(db, user_id)
    if not user:
        raise HTTPException(401, "user_not_found")

    store = get_oauth_store()
    code = await store.save_auth_code(
        AuthCodeData(
            user_id=user.id,
            client_id=client_id,
            redirect_uri=redirect_uri,
            scope=scope,
            code_challenge=code_challenge,
            code_challenge_method=code_challenge_method,
            nonce=nonce,
        )
    )

    callback_params = {"code": code}
    if state:
        callback_params["state"] = state

    sep = "&" if "?" in redirect_uri else "?"
    return RedirectResponse(f"{redirect_uri}{sep}{urlencode(callback_params)}")


@router.post("/token")
async def token_exchange(
    request: Request,
    grant_type: str = Form(...),
    code: str | None = Form(None),
    redirect_uri: str | None = Form(None),
    client_id: str = Form(...),
    client_secret: str | None = Form(None),
    code_verifier: str | None = Form(None),
    refresh_token: str | None = Form(None),
    db: AsyncSession = Depends(get_db),
):
    from app.identity.provider import verify_client_secret
    from app.oauth.store import verify_pkce
    from app.services.audit import log_audit
    from app.tokens.platform_jwt import get_jwt_service

    client = await get_oauth_client(db, client_id)
    if not client or not client.enabled:
        raise HTTPException(401, "invalid_client")

    if client.token_endpoint_auth == "client_secret_post":
        if not client_secret or not verify_client_secret(client_secret, client.client_secret_hash):
            raise HTTPException(401, "invalid_client")

    jwt_svc = get_jwt_service()

    if grant_type == "authorization_code":
        if not code or not redirect_uri or not code_verifier:
            raise HTTPException(400, "invalid_request")

        store = get_oauth_store()
        code_data = await store.consume_auth_code(code)
        if not code_data:
            raise HTTPException(400, "invalid_grant")
        if code_data.client_id != client_id or code_data.redirect_uri != redirect_uri:
            raise HTTPException(400, "invalid_grant")
        if not verify_pkce(code_verifier, code_data.code_challenge, code_data.code_challenge_method):
            raise HTTPException(400, "invalid_grant")

        user = await get_user_by_id(db, code_data.user_id)
        if not user:
            raise HTTPException(400, "invalid_grant")

        scope = code_data.scope or client.scopes
        access_token = jwt_svc.issue_access_token(
            user.id, client_id, user.email, scope, user.email_verified, user.name
        )
        id_token = jwt_svc.issue_id_token(
            user.id, client_id, user.email, user.email_verified, user.name, code_data.nonce
        )
        new_refresh = jwt_svc.issue_refresh_token(user.id, client_id)

        await log_audit(
            db,
            "TOKEN_ISSUED",
            actor_id=user.id,
            ip=request.client.host if request.client else None,
            user_agent=request.headers.get("user-agent"),
            metadata={"client_id": client_id, "grant_type": grant_type},
        )

        return {
            "access_token": access_token,
            "id_token": id_token,
            "token_type": "Bearer",
            "expires_in": 900,
            "refresh_token": new_refresh,
            "scope": scope,
        }

    if grant_type == "refresh_token":
        if not refresh_token:
            raise HTTPException(400, "invalid_request")
        try:
            claims = jwt_svc.decode_token(refresh_token, audience=client_id)
        except Exception:
            raise HTTPException(400, "invalid_grant")
        if claims.get("token_use") != "refresh":
            raise HTTPException(400, "invalid_grant")

        user = await get_user_by_id(db, claims["sub"])
        if not user:
            raise HTTPException(400, "invalid_grant")

        scope = client.scopes
        access_token = jwt_svc.issue_access_token(
            user.id, client_id, user.email, scope, user.email_verified, user.name
        )
        id_token = jwt_svc.issue_id_token(user.id, client_id, user.email, user.email_verified, user.name)
        new_refresh = jwt_svc.issue_refresh_token(user.id, client_id)

        return {
            "access_token": access_token,
            "id_token": id_token,
            "token_type": "Bearer",
            "expires_in": 900,
            "refresh_token": new_refresh,
            "scope": scope,
        }

    raise HTTPException(400, "unsupported_grant_type")
