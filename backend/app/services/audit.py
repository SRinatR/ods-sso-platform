import json
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import AuditLog


async def log_audit(
    db: AsyncSession,
    event_type: str,
    actor_id: Optional[str] = None,
    ip: Optional[str] = None,
    user_agent: Optional[str] = None,
    metadata: Optional[dict] = None,
    trace_id: Optional[str] = None,
) -> None:
    entry = AuditLog(
        event_type=event_type,
        actor_id=actor_id,
        ip=ip,
        user_agent=user_agent,
        metadata_json=json.dumps(metadata) if metadata else None,
        trace_id=trace_id,
    )
    db.add(entry)
    await db.commit()


async def get_user_by_email(db: AsyncSession, email: str):
    from app.db.models import User

    result = await db.execute(select(User).where(User.email == email))
    return result.scalar_one_or_none()


async def get_user_by_id(db: AsyncSession, user_id: str):
    from app.db.models import User

    result = await db.execute(select(User).where(User.id == user_id))
    return result.scalar_one_or_none()


async def get_oauth_client(db: AsyncSession, client_id: str):
    from app.db.models import OAuthClient

    result = await db.execute(select(OAuthClient).where(OAuthClient.client_id == client_id))
    return result.scalar_one_or_none()
