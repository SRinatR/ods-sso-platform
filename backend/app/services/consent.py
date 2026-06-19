from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import ConsentVersion, User, UserConsent
from app.errors import AppError
from app.middleware import client_ip
from app.repositories.consents import ConsentRepository
from app.repositories.oauth import OAuthRepository
from app.security import utcnow
from app.services.audit import write_audit_log

DEFAULT_CONSENT_TITLE = "Application access"
DEFAULT_CONSENT_BODY = (
    "The application receives only the scopes shown on the consent screen. "
    "Access can be revoked at any time from Connected Applications."
)


class ConsentService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db
        self.repo = ConsentRepository(db)

    async def ensure_active_version(self) -> ConsentVersion:
        version = await self.repo.active_version("ru")
        if version:
            return version
        version = ConsentVersion(
            version="1.0",
            locale="ru",
            title=DEFAULT_CONSENT_TITLE,
            body=DEFAULT_CONSENT_BODY,
            active=True,
        )
        self.db.add(version)
        await self.db.flush()
        return version

    async def required_scopes(
        self, user_id: str, client_id: str, requested_scopes: list[str], force: bool = False
    ) -> list[str]:
        if force:
            return requested_scopes
        consent = await self.repo.get(user_id, client_id)
        if not consent or consent.status != "granted":
            return requested_scopes
        return sorted(set(requested_scopes) - set(consent.scopes))

    async def grant(
        self,
        request: Request,
        user: User,
        client_id: str,
        scopes: list[str],
    ) -> UserConsent:
        version = await self.ensure_active_version()
        consent = await self.repo.grant(
            user.id,
            client_id,
            version.id,
            scopes,
            utcnow(),
            client_ip(request),
            request.headers.get("user-agent"),
        )
        await write_audit_log(
            self.db,
            request,
            "OAUTH_CONSENT_GRANTED",
            actor_id=user.id,
            subject_id=user.id,
            client_id=client_id,
            details={"scopes": scopes, "consent_version": version.version},
        )
        return consent

    async def revoke(
        self,
        request: Request,
        user: User,
        consent_id: str,
    ) -> None:
        consent = await self.db.get(UserConsent, consent_id)
        if not consent or consent.user_id != user.id or consent.status != "granted":
            raise AppError(404, "connected_application_not_found", "Application was not found")
        consent.status = "revoked"
        consent.revoked_at = utcnow()
        await OAuthRepository(self.db).revoke_for_user_client(
            user.id, consent.client_id, consent.revoked_at
        )
        await write_audit_log(
            self.db,
            request,
            "CONSENT_REVOKED",
            actor_id=user.id,
            subject_id=user.id,
            client_id=consent.client_id,
            details={"consent_id": consent.id},
        )
