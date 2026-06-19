from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.dependencies import Principal, current_principal
from app.errors import AppError
from app.middleware import client_ip
from app.schemas import (
    BackupCodesResponse,
    LoginResponse,
    MFAChallengeRequest,
    StepUpRequest,
    TOTPEnableRequest,
    TOTPSetupResponse,
)
from app.services.audit import write_audit_log
from app.services.identity import IdentityService
from app.services.mfa import MFAService
from app.services.rate_limit import MFA_LIMIT, enforce_rate_limit

router = APIRouter(prefix="/api/v1/auth/mfa", tags=["MFA"])


@router.post("/verify", response_model=LoginResponse)
async def verify_login_mfa(
    body: MFAChallengeRequest,
    request: Request,
    response: Response,
    db: AsyncSession = Depends(get_db),
) -> LoginResponse:
    await enforce_rate_limit(MFA_LIMIT, client_ip(request))
    user = await MFAService(db).verify_challenge(body.challenge_token, body.code, body.method)
    await IdentityService(db).complete_login(request, response, user, mfa_completed=True)
    await write_audit_log(
        db,
        request,
        "MFA_VERIFIED",
        actor_id=user.id,
        subject_id=user.id,
        details={"method": body.method},
    )
    await db.commit()
    return LoginResponse(user_id=user.id, email=user.email)


@router.post("/totp/setup", response_model=TOTPSetupResponse)
async def setup_totp(
    request: Request,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> TOTPSetupResponse:
    secret, uri = await MFAService(db).setup_totp(principal.user)
    await write_audit_log(
        db,
        request,
        "MFA_SETUP_STARTED",
        actor_id=principal.user.id,
        subject_id=principal.user.id,
    )
    await db.commit()
    return TOTPSetupResponse(secret=secret, provisioning_uri=uri, expires_in=600)


@router.post("/totp/enable", response_model=BackupCodesResponse)
async def enable_totp(
    body: TOTPEnableRequest,
    request: Request,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> BackupCodesResponse:
    await enforce_rate_limit(MFA_LIMIT, principal.user.id)
    codes = await MFAService(db).enable_totp(principal.user, body.code)
    principal.session.mfa_completed_at = principal.session.last_seen_at
    await write_audit_log(
        db,
        request,
        "MFA_ENABLED",
        actor_id=principal.user.id,
        subject_id=principal.user.id,
        details={"method": "totp"},
    )
    await db.commit()
    return BackupCodesResponse(backup_codes=codes)


@router.post("/backup/regenerate", response_model=BackupCodesResponse)
async def regenerate_backup_codes(
    body: StepUpRequest,
    request: Request,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> BackupCodesResponse:
    from app.security import verify_password

    if not verify_password(body.password, principal.user.password_hash):
        raise AppError(401, "invalid_credentials", "Step-up authentication failed")
    await MFAService(db).verify_step_up(principal.user, body.code)
    codes = await MFAService(db).regenerate_backup_codes(principal.user.id)
    await write_audit_log(
        db,
        request,
        "BACKUP_CODES_REGENERATED",
        actor_id=principal.user.id,
        subject_id=principal.user.id,
    )
    await db.commit()
    return BackupCodesResponse(backup_codes=codes)
