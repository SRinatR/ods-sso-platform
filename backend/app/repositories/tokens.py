from datetime import datetime

from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import EmailVerificationToken, PasswordResetToken


class AccountTokenRepository:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def add_email_token(
        self, token_id: str, user_id: str, secret_hash: str, expires_at: datetime
    ) -> EmailVerificationToken:
        await self.db.execute(
            update(EmailVerificationToken)
            .where(
                EmailVerificationToken.user_id == user_id,
                EmailVerificationToken.used_at.is_(None),
            )
            .values(used_at=datetime.now(expires_at.tzinfo))
        )
        item = EmailVerificationToken(
            id=token_id,
            user_id=user_id,
            secret_hash=secret_hash,
            expires_at=expires_at,
        )
        self.db.add(item)
        await self.db.flush()
        return item

    async def get_email_token(self, token_id: str) -> EmailVerificationToken | None:
        return await self.db.get(EmailVerificationToken, token_id)

    async def add_reset_token(
        self, token_id: str, user_id: str, secret_hash: str, expires_at: datetime
    ) -> PasswordResetToken:
        await self.db.execute(
            update(PasswordResetToken)
            .where(
                PasswordResetToken.user_id == user_id,
                PasswordResetToken.used_at.is_(None),
            )
            .values(used_at=datetime.now(expires_at.tzinfo))
        )
        item = PasswordResetToken(
            id=token_id,
            user_id=user_id,
            secret_hash=secret_hash,
            expires_at=expires_at,
        )
        self.db.add(item)
        await self.db.flush()
        return item

    async def get_reset_token(self, token_id: str) -> PasswordResetToken | None:
        return await self.db.get(PasswordResetToken, token_id)

    async def invalidate_reset_tokens(self, user_id: str, now: datetime) -> None:
        await self.db.execute(
            update(PasswordResetToken)
            .where(
                PasswordResetToken.user_id == user_id,
                PasswordResetToken.used_at.is_(None),
            )
            .values(used_at=now)
        )
