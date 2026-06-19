from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import LoginHistory, User


class UserRepository:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def get_by_id(self, user_id: str) -> User | None:
        return await self.db.get(User, user_id)

    async def get_by_email(self, email: str) -> User | None:
        result = await self.db.execute(select(User).where(User.email == email.lower()))
        return result.scalar_one_or_none()

    async def create(self, email: str, password_hash: str, name: str, role: str = "user") -> User:
        user = User(
            email=email.lower(),
            password_hash=password_hash,
            name=name.strip(),
            role=role,
        )
        self.db.add(user)
        await self.db.flush()
        return user

    async def list_users(self, query: str | None, offset: int, limit: int) -> list[User]:
        statement = select(User).order_by(User.created_at.desc()).offset(offset).limit(limit)
        if query:
            pattern = f"%{query.lower()}%"
            statement = statement.where(
                func.lower(User.email).like(pattern) | func.lower(User.name).like(pattern)
            )
        result = await self.db.execute(statement)
        return list(result.scalars())

    async def record_login(
        self,
        email: str,
        success: bool,
        ip_address: str,
        user_agent: str | None,
        user_id: str | None = None,
        failure_reason: str | None = None,
    ) -> LoginHistory:
        item = LoginHistory(
            user_id=user_id,
            email=email.lower(),
            success=success,
            failure_reason=failure_reason,
            ip_address=ip_address,
            user_agent=user_agent,
        )
        self.db.add(item)
        await self.db.flush()
        return item

    async def login_history(self, user_id: str, limit: int = 100) -> list[LoginHistory]:
        result = await self.db.execute(
            select(LoginHistory)
            .where(LoginHistory.user_id == user_id)
            .order_by(LoginHistory.created_at.desc())
            .limit(limit)
        )
        return list(result.scalars())

    async def mark_verified(self, user: User, verified_at: datetime) -> None:
        user.email_verified_at = verified_at
        await self.db.flush()
