from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import Session


class SessionRepository:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def create(
        self,
        session_id: str,
        user_id: str,
        secret_hash: str,
        ip_address: str,
        user_agent: str | None,
        expires_at: datetime,
        mfa_completed_at: datetime | None,
    ) -> Session:
        session = Session(
            id=session_id,
            user_id=user_id,
            secret_hash=secret_hash,
            ip_address=ip_address,
            user_agent=user_agent,
            expires_at=expires_at,
            mfa_completed_at=mfa_completed_at,
        )
        self.db.add(session)
        await self.db.flush()
        return session

    async def get(self, session_id: str) -> Session | None:
        return await self.db.get(Session, session_id)

    async def list_active(self, user_id: str, now: datetime) -> list[Session]:
        result = await self.db.execute(
            select(Session)
            .where(
                Session.user_id == user_id,
                Session.revoked_at.is_(None),
                Session.expires_at > now,
            )
            .order_by(Session.last_seen_at.desc())
        )
        return list(result.scalars())

    async def revoke(self, session_id: str, user_id: str, now: datetime) -> bool:
        result = await self.db.execute(
            update(Session)
            .where(
                Session.id == session_id,
                Session.user_id == user_id,
                Session.revoked_at.is_(None),
            )
            .values(revoked_at=now)
        )
        return bool(result.rowcount)

    async def revoke_all(self, user_id: str, now: datetime, except_id: str | None = None) -> int:
        statement = (
            update(Session)
            .where(Session.user_id == user_id, Session.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        if except_id:
            statement = statement.where(Session.id != except_id)
        result = await self.db.execute(statement)
        return int(result.rowcount or 0)
