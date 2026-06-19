from typing import Any

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.middleware import client_ip
from app.repositories.audit import AuditRepository
from app.security import sanitize_for_log


async def write_audit_log(
    db: AsyncSession,
    request: Request,
    event_type: str,
    *,
    actor_id: str | None = None,
    subject_id: str | None = None,
    client_id: str | None = None,
    details: dict[str, Any] | None = None,
) -> None:
    await AuditRepository(db).write(
        event_type=event_type,
        request_id=request.state.request_id,
        actor_id=actor_id,
        subject_id=subject_id,
        client_id=client_id,
        ip_address=client_ip(request),
        user_agent=request.headers.get("user-agent"),
        details=sanitize_for_log(details or {}),
    )
