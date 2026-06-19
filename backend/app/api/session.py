from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import get_db
from app.dependencies import Principal, current_principal
from app.errors import AppError
from app.repositories.sessions import SessionRepository
from app.repositories.users import UserRepository
from app.schemas import LoginHistoryResponse, MessageResponse, SessionResponse
from app.security import as_utc, utcnow
from app.services.audit import write_audit_log
from app.services.session import revoke_session

router = APIRouter(prefix="/api/v1/account", tags=["Sessions"])


@router.get("/sessions", response_model=list[SessionResponse])
async def list_sessions(
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> list[SessionResponse]:
    now = utcnow()
    sessions = await SessionRepository(db).list_active(principal.user.id, now)
    return [
        SessionResponse(
            id=item.id,
            ip_address=item.ip_address,
            user_agent=item.user_agent,
            created_at=item.created_at,
            last_seen_at=item.last_seen_at,
            expires_at=item.expires_at,
            current=item.id == principal.session.id,
            mfa_completed=item.mfa_completed_at is not None,
            step_up_valid=bool(
                item.step_up_at
                and (now - as_utc(item.step_up_at)).total_seconds() <= settings.step_up_ttl
            ),
        )
        for item in sessions
    ]


@router.delete("/sessions/{session_id}", response_model=MessageResponse)
async def delete_session(
    session_id: str,
    request: Request,
    response: Response,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    revoked = await revoke_session(
        db,
        response,
        principal.user.id,
        session_id,
        clear_cookie=session_id == principal.session.id,
    )
    if not revoked:
        raise AppError(404, "session_not_found", "Session was not found")
    await write_audit_log(
        db,
        request,
        "SESSION_REVOKED",
        actor_id=principal.user.id,
        subject_id=principal.user.id,
        details={"session_id": session_id},
    )
    await db.commit()
    return MessageResponse(message="Session revoked")


@router.get("/login-history", response_model=list[LoginHistoryResponse])
async def login_history(
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> list[LoginHistoryResponse]:
    items = await UserRepository(db).login_history(principal.user.id)
    return [
        LoginHistoryResponse(
            id=item.id,
            email=item.email,
            success=item.success,
            failure_reason=item.failure_reason,
            ip_address=item.ip_address,
            user_agent=item.user_agent,
            created_at=item.created_at,
        )
        for item in items
    ]
