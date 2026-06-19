from dataclasses import dataclass

from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import get_db
from app.db.models import Session, User
from app.errors import AppError
from app.middleware import client_ip
from app.security import as_utc, utcnow
from app.services.rate_limit import ADMIN_LIMIT, enforce_rate_limit
from app.services.session import authenticate_session


@dataclass(frozen=True)
class Principal:
    user: User
    session: Session


async def current_principal(request: Request, db: AsyncSession = Depends(get_db)) -> Principal:
    user, session = await authenticate_session(db, request)
    return Principal(user=user, session=session)


async def admin_principal(
    request: Request,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> Principal:
    from app.services.audit import write_audit_log

    await enforce_rate_limit(ADMIN_LIMIT, f"{principal.user.id}:{client_ip(request)}")
    if principal.user.role not in {"admin", "security_admin"}:
        await write_audit_log(
            db,
            request,
            "ADMIN_ACCESS_DENIED",
            actor_id=principal.user.id,
            subject_id=principal.user.id,
            details={"reason": "role"},
        )
        await db.commit()
        raise AppError(403, "admin_required", "Administrator access is required")
    if not principal.user.mfa_enabled or not principal.session.mfa_completed_at:
        await write_audit_log(
            db,
            request,
            "ADMIN_ACCESS_DENIED",
            actor_id=principal.user.id,
            subject_id=principal.user.id,
            details={"reason": "mfa"},
        )
        await db.commit()
        raise AppError(403, "mfa_required", "Administrator MFA is required")
    now = utcnow()
    if (
        not principal.session.step_up_at
        or (now - as_utc(principal.session.step_up_at)).total_seconds() > settings.step_up_ttl
    ):
        await write_audit_log(
            db,
            request,
            "ADMIN_ACCESS_DENIED",
            actor_id=principal.user.id,
            subject_id=principal.user.id,
            details={"reason": "step_up"},
        )
        await db.commit()
        raise AppError(403, "step_up_required", "Recent step-up authentication is required")
    return principal
