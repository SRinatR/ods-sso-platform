from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.dependencies import Principal, current_principal
from app.repositories.consents import ConsentRepository
from app.schemas import ConnectedApplicationResponse, MessageResponse
from app.services.consent import ConsentService

router = APIRouter(prefix="/api/v1/account", tags=["Consent and connected applications"])


@router.get("/connected-apps", response_model=list[ConnectedApplicationResponse])
async def connected_apps(
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> list[ConnectedApplicationResponse]:
    items = await ConsentRepository(db).list_connected(principal.user.id)
    return [
        ConnectedApplicationResponse(
            consent_id=consent.id,
            client_id=client.client_id,
            name=client.name,
            scopes=consent.scopes,
            granted_at=consent.granted_at,
        )
        for consent, client in items
    ]


@router.delete("/connected-apps/{consent_id}", response_model=MessageResponse)
async def revoke_connected_app(
    consent_id: str,
    request: Request,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    await ConsentService(db).revoke(request, principal.user, consent_id)
    await db.commit()
    return MessageResponse(message="Application access revoked")
