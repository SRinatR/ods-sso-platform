from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import ConsentVersion, OAuthClient, UserConsent


class ConsentRepository:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def active_version(self, locale: str = "ru") -> ConsentVersion | None:
        result = await self.db.execute(
            select(ConsentVersion)
            .where(ConsentVersion.locale == locale, ConsentVersion.active.is_(True))
            .order_by(ConsentVersion.created_at.desc())
            .limit(1)
        )
        return result.scalar_one_or_none()

    async def get(self, user_id: str, client_id: str) -> UserConsent | None:
        result = await self.db.execute(
            select(UserConsent).where(
                UserConsent.user_id == user_id,
                UserConsent.client_id == client_id,
            )
        )
        return result.scalar_one_or_none()

    async def grant(
        self,
        user_id: str,
        client_id: str,
        version_id: str,
        scopes: list[str],
        now: datetime,
        ip_address: str,
        user_agent: str | None,
    ) -> UserConsent:
        consent = await self.get(user_id, client_id)
        if consent:
            consent.scopes = sorted(set(consent.scopes) | set(scopes))
            consent.status = "granted"
            consent.granted_at = now
            consent.revoked_at = None
            consent.consent_version_id = version_id
            consent.ip_address = ip_address
            consent.user_agent = user_agent
        else:
            consent = UserConsent(
                user_id=user_id,
                client_id=client_id,
                consent_version_id=version_id,
                scopes=sorted(set(scopes)),
                status="granted",
                granted_at=now,
                ip_address=ip_address,
                user_agent=user_agent,
            )
            self.db.add(consent)
        await self.db.flush()
        return consent

    async def list_connected(self, user_id: str) -> list[tuple[UserConsent, OAuthClient]]:
        result = await self.db.execute(
            select(UserConsent, OAuthClient)
            .join(OAuthClient, OAuthClient.client_id == UserConsent.client_id)
            .where(UserConsent.user_id == user_id, UserConsent.status == "granted")
            .order_by(UserConsent.granted_at.desc())
        )
        return list(result.tuples())
