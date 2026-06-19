from datetime import datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import AuditLog


class AuditRepository:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def write(
        self,
        event_type: str,
        request_id: str,
        actor_id: str | None,
        subject_id: str | None,
        client_id: str | None,
        ip_address: str | None,
        user_agent: str | None,
        details: dict[str, Any],
    ) -> AuditLog:
        entry = AuditLog(
            event_type=event_type,
            request_id=request_id,
            actor_id=actor_id,
            subject_id=subject_id,
            client_id=client_id,
            ip_address=ip_address,
            user_agent=user_agent,
            details=details,
        )
        self.db.add(entry)
        await self.db.flush()
        return entry

    async def list(
        self,
        event_type: str | None = None,
        actor_id: str | None = None,
        since: datetime | None = None,
        limit: int = 200,
    ) -> list[AuditLog]:
        statement = select(AuditLog).order_by(AuditLog.created_at.desc()).limit(limit)
        if event_type:
            statement = statement.where(AuditLog.event_type == event_type)
        if actor_id:
            statement = statement.where(AuditLog.actor_id == actor_id)
        if since:
            statement = statement.where(AuditLog.created_at >= since)
        result = await self.db.execute(statement)
        return list(result.scalars())
