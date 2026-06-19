import secrets
from datetime import timedelta
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.db.models import (
    AuditLog,
    LoginHistory,
    OAuthClient,
    SecurityPolicy,
    Session,
    User,
    UserTwoFactorMethod,
)
from app.dependencies import Principal, admin_principal
from app.errors import AppError
from app.repositories.audit import AuditRepository
from app.repositories.oauth import OAuthRepository
from app.repositories.users import UserRepository
from app.schemas import (
    AdminDashboardResponse,
    AdminUserUpdate,
    AuditResponse,
    MessageResponse,
    OAuthClientCreate,
    OAuthClientCreatedResponse,
    OAuthClientResponse,
    OAuthClientUpdate,
    SecurityPolicyResponse,
    SecurityPolicyUpdate,
    UserResponse,
)
from app.security import utcnow
from app.services.audit import write_audit_log
from app.services.oauth import create_client_secret
from app.services.session import revoke_all_sessions

router = APIRouter(
    prefix="/api/v1/admin",
    tags=["Administration"],
    dependencies=[Depends(admin_principal)],
)


def user_response(user: User) -> UserResponse:
    return UserResponse(
        id=user.id,
        email=user.email,
        name=user.name,
        email_verified=user.email_verified,
        status=user.status,
        role=user.role,
        mfa_enabled=user.mfa_enabled,
        created_at=user.created_at,
    )


def client_payload(client: OAuthClient) -> dict[str, Any]:
    return {
        "id": client.id,
        "client_id": client.client_id,
        "name": client.name,
        "description": client.description,
        "redirect_uris": client.redirect_uris,
        "allowed_scopes": client.allowed_scopes,
        "grant_types": client.grant_types,
        "token_endpoint_auth_method": client.token_endpoint_auth_method,
        "is_public": client.is_public,
        "require_pkce": client.require_pkce,
        "enabled": client.enabled,
        "created_at": client.created_at,
    }


def client_response(client: OAuthClient) -> OAuthClientResponse:
    return OAuthClientResponse(**client_payload(client))


def client_created_response(client: OAuthClient, secret: str | None) -> OAuthClientCreatedResponse:
    return OAuthClientCreatedResponse(**client_payload(client), client_secret=secret)


@router.get("/dashboard", response_model=AdminDashboardResponse)
async def dashboard(db: AsyncSession = Depends(get_db)) -> AdminDashboardResponse:
    now = utcnow()
    since = now - timedelta(hours=24)

    async def count(statement: Any) -> int:
        return int((await db.execute(statement)).scalar_one())

    return AdminDashboardResponse(
        users_total=await count(select(func.count()).select_from(User)),
        users_active=await count(
            select(func.count()).select_from(User).where(User.status == "active")
        ),
        active_sessions=await count(
            select(func.count())
            .select_from(Session)
            .where(Session.revoked_at.is_(None), Session.expires_at > now)
        ),
        oauth_clients=await count(select(func.count()).select_from(OAuthClient)),
        failed_logins_24h=await count(
            select(func.count())
            .select_from(LoginHistory)
            .where(LoginHistory.success.is_(False), LoginHistory.created_at >= since)
        ),
        audit_events_24h=await count(
            select(func.count()).select_from(AuditLog).where(AuditLog.created_at >= since)
        ),
    )


@router.get("/users", response_model=list[UserResponse])
async def list_users(
    query: str | None = None,
    offset: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=500),
    db: AsyncSession = Depends(get_db),
) -> list[UserResponse]:
    users = await UserRepository(db).list_users(query, offset, limit)
    return [user_response(user) for user in users]


@router.patch("/users/{user_id}", response_model=UserResponse)
async def update_user(
    user_id: str,
    body: AdminUserUpdate,
    request: Request,
    principal: Principal = Depends(admin_principal),
    db: AsyncSession = Depends(get_db),
) -> UserResponse:
    user = await UserRepository(db).get_by_id(user_id)
    if not user:
        raise AppError(404, "user_not_found", "User was not found")
    changes: dict[str, str] = {}
    if body.status is not None and body.status != user.status:
        changes["status"] = body.status
        user.status = body.status
        if body.status != "active":
            await revoke_all_sessions(db, user.id)
    if body.role is not None and body.role != user.role:
        changes["role"] = body.role
        user.role = body.role
    if not changes:
        return user_response(user)
    await write_audit_log(
        db,
        request,
        "ADMIN_USER_UPDATED",
        actor_id=principal.user.id,
        subject_id=user.id,
        details=changes,
    )
    await db.commit()
    return user_response(user)


@router.post("/users/{user_id}/sessions/revoke", response_model=MessageResponse)
async def admin_revoke_user_sessions(
    user_id: str,
    request: Request,
    principal: Principal = Depends(admin_principal),
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    user = await UserRepository(db).get_by_id(user_id)
    if not user:
        raise AppError(404, "user_not_found", "User was not found")
    count = await revoke_all_sessions(db, user_id)
    await write_audit_log(
        db,
        request,
        "ADMIN_SESSIONS_REVOKED",
        actor_id=principal.user.id,
        subject_id=user_id,
        details={"revoked_sessions": count},
    )
    await db.commit()
    return MessageResponse(message="User sessions revoked")


@router.post("/users/{user_id}/mfa/reset", response_model=MessageResponse)
async def admin_reset_mfa(
    user_id: str,
    request: Request,
    principal: Principal = Depends(admin_principal),
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    user = await UserRepository(db).get_by_id(user_id)
    if not user:
        raise AppError(404, "user_not_found", "User was not found")
    methods = await db.execute(
        select(UserTwoFactorMethod).where(UserTwoFactorMethod.user_id == user_id)
    )
    for method in methods.scalars():
        await db.delete(method)
    user.mfa_enabled = False
    await revoke_all_sessions(db, user.id)
    await write_audit_log(
        db,
        request,
        "ADMIN_MFA_RESET",
        actor_id=principal.user.id,
        subject_id=user.id,
    )
    await db.commit()
    return MessageResponse(message="User MFA reset")


@router.get("/oauth-clients", response_model=list[OAuthClientResponse])
async def list_oauth_clients(
    db: AsyncSession = Depends(get_db),
) -> list[OAuthClientResponse]:
    return [client_response(item) for item in await OAuthRepository(db).list_clients()]


@router.post(
    "/oauth-clients",
    response_model=OAuthClientCreatedResponse,
    status_code=201,
)
async def create_oauth_client(
    body: OAuthClientCreate,
    request: Request,
    principal: Principal = Depends(admin_principal),
    db: AsyncSession = Depends(get_db),
) -> OAuthClientCreatedResponse:
    client_id = f"cli_{secrets.token_urlsafe(18)}"
    secret, secret_hash = (None, None) if body.is_public else create_client_secret()
    client = OAuthClient(
        client_id=client_id,
        client_secret_hash=secret_hash,
        name=body.name,
        description=body.description,
        redirect_uris=body.redirect_uris,
        allowed_scopes=body.allowed_scopes,
        grant_types=["authorization_code", "refresh_token"],
        token_endpoint_auth_method="none" if body.is_public else body.token_endpoint_auth_method,
        is_public=body.is_public,
        require_pkce=True,
    )
    db.add(client)
    await db.flush()
    await write_audit_log(
        db,
        request,
        "OAUTH_CLIENT_CREATED",
        actor_id=principal.user.id,
        subject_id=client.id,
        client_id=client.client_id,
    )
    await db.commit()
    return client_created_response(client, secret)


@router.patch("/oauth-clients/{client_id}", response_model=OAuthClientResponse)
async def update_oauth_client(
    client_id: str,
    body: OAuthClientUpdate,
    request: Request,
    principal: Principal = Depends(admin_principal),
    db: AsyncSession = Depends(get_db),
) -> OAuthClientResponse:
    client = await OAuthRepository(db).get_client(client_id)
    if not client:
        raise AppError(404, "client_not_found", "OAuth client was not found")
    changes = body.model_dump(exclude_unset=True)
    for key, value in changes.items():
        setattr(client, key, value)
    await write_audit_log(
        db,
        request,
        "OAUTH_CLIENT_UPDATED",
        actor_id=principal.user.id,
        subject_id=client.id,
        client_id=client.client_id,
        details={"fields": sorted(changes)},
    )
    await db.commit()
    return client_response(client)


@router.post(
    "/oauth-clients/{client_id}/rotate-secret",
    response_model=OAuthClientCreatedResponse,
)
async def rotate_oauth_client_secret(
    client_id: str,
    request: Request,
    principal: Principal = Depends(admin_principal),
    db: AsyncSession = Depends(get_db),
) -> OAuthClientCreatedResponse:
    client = await OAuthRepository(db).get_client(client_id)
    if not client or client.is_public:
        raise AppError(404, "client_not_found", "Confidential OAuth client was not found")
    secret, secret_hash = create_client_secret()
    client.client_secret_hash = secret_hash
    await write_audit_log(
        db,
        request,
        "OAUTH_CLIENT_SECRET_ROTATED",
        actor_id=principal.user.id,
        subject_id=client.id,
        client_id=client.client_id,
    )
    await db.commit()
    return client_created_response(client, secret)


@router.get("/sessions")
async def admin_sessions(
    user_id: str | None = None,
    db: AsyncSession = Depends(get_db),
) -> list[dict[str, object]]:
    statement = select(Session).order_by(Session.last_seen_at.desc()).limit(500)
    if user_id:
        statement = statement.where(Session.user_id == user_id)
    sessions = list((await db.execute(statement)).scalars())
    return [
        {
            "id": item.id,
            "user_id": item.user_id,
            "ip_address": item.ip_address,
            "user_agent": item.user_agent,
            "created_at": item.created_at,
            "last_seen_at": item.last_seen_at,
            "expires_at": item.expires_at,
            "revoked_at": item.revoked_at,
        }
        for item in sessions
    ]


@router.get("/audit", response_model=list[AuditResponse])
async def audit_logs(
    event_type: str | None = None,
    actor_id: str | None = None,
    limit: int = Query(default=200, ge=1, le=1000),
    db: AsyncSession = Depends(get_db),
) -> list[AuditResponse]:
    return [
        AuditResponse.model_validate(item)
        for item in await AuditRepository(db).list(event_type, actor_id, limit=limit)
    ]


@router.get("/security-policies", response_model=list[SecurityPolicyResponse])
async def security_policies(
    db: AsyncSession = Depends(get_db),
) -> list[SecurityPolicyResponse]:
    result = await db.execute(select(SecurityPolicy).order_by(SecurityPolicy.key))
    return [
        SecurityPolicyResponse(
            key=item.key,
            value=item.value,
            updated_by=item.updated_by,
            updated_at=item.updated_at,
        )
        for item in result.scalars()
    ]


@router.put("/security-policies/{key}", response_model=SecurityPolicyResponse)
async def update_security_policy(
    key: str,
    body: SecurityPolicyUpdate,
    request: Request,
    principal: Principal = Depends(admin_principal),
    db: AsyncSession = Depends(get_db),
) -> SecurityPolicyResponse:
    policy = await db.get(SecurityPolicy, key)
    if policy:
        policy.value = body.value
        policy.updated_by = principal.user.id
    else:
        policy = SecurityPolicy(key=key, value=body.value, updated_by=principal.user.id)
        db.add(policy)
    await write_audit_log(
        db,
        request,
        "SECURITY_POLICY_UPDATED",
        actor_id=principal.user.id,
        subject_id=key,
        details={"policy": key},
    )
    await db.commit()
    await db.refresh(policy)
    return SecurityPolicyResponse(
        key=policy.key,
        value=policy.value,
        updated_by=policy.updated_by,
        updated_at=policy.updated_at,
    )
