from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import (
    AuthorizationCode,
    OAuthAccessToken,
    OAuthAuthorizationRequest,
    OAuthClient,
    RefreshToken,
)


class OAuthRepository:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def get_client(self, client_id: str) -> OAuthClient | None:
        result = await self.db.execute(
            select(OAuthClient).where(OAuthClient.client_id == client_id)
        )
        return result.scalar_one_or_none()

    async def list_clients(self) -> list[OAuthClient]:
        result = await self.db.execute(select(OAuthClient).order_by(OAuthClient.created_at.desc()))
        return list(result.scalars())

    async def add_request(self, request: OAuthAuthorizationRequest) -> None:
        self.db.add(request)
        await self.db.flush()

    async def get_request(self, request_id: str) -> OAuthAuthorizationRequest | None:
        return await self.db.get(OAuthAuthorizationRequest, request_id)

    async def add_code(self, code: AuthorizationCode) -> None:
        self.db.add(code)
        await self.db.flush()

    async def get_code(self, code_id: str) -> AuthorizationCode | None:
        return await self.db.get(AuthorizationCode, code_id)

    async def add_access_token(self, token: OAuthAccessToken) -> None:
        self.db.add(token)
        await self.db.flush()

    async def get_access_token(self, jti: str) -> OAuthAccessToken | None:
        return await self.db.get(OAuthAccessToken, jti)

    async def add_refresh_token(self, token: RefreshToken) -> None:
        self.db.add(token)
        await self.db.flush()

    async def get_refresh_token(self, token_id: str) -> RefreshToken | None:
        return await self.db.get(RefreshToken, token_id)

    async def revoke_family(self, family_id: str, now: datetime) -> int:
        result = await self.db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        return int(result.rowcount or 0)

    async def revoke_for_user_client(self, user_id: str, client_id: str, now: datetime) -> None:
        await self.db.execute(
            update(RefreshToken)
            .where(
                RefreshToken.user_id == user_id,
                RefreshToken.client_id == client_id,
                RefreshToken.revoked_at.is_(None),
            )
            .values(revoked_at=now)
        )
        await self.db.execute(
            update(OAuthAccessToken)
            .where(
                OAuthAccessToken.user_id == user_id,
                OAuthAccessToken.client_id == client_id,
                OAuthAccessToken.revoked_at.is_(None),
            )
            .values(revoked_at=now)
        )

    async def revoke_session_tokens(self, session_id: str, now: datetime) -> None:
        await self.db.execute(
            update(RefreshToken)
            .where(RefreshToken.session_id == session_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        await self.db.execute(
            update(OAuthAccessToken)
            .where(
                OAuthAccessToken.session_id == session_id,
                OAuthAccessToken.revoked_at.is_(None),
            )
            .values(revoked_at=now)
        )
