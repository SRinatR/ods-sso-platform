from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.dependencies import Principal, current_principal
from app.errors import AppError
from app.middleware import client_ip
from app.schemas import (
    ForgotPasswordRequest,
    LoginRequest,
    LoginResponse,
    MessageResponse,
    RegisterRequest,
    ResendVerificationRequest,
    ResetPasswordRequest,
    StepUpRequest,
    UserResponse,
    VerifyEmailRequest,
)
from app.security import utcnow, verify_password
from app.services.audit import write_audit_log
from app.services.identity import IdentityService
from app.services.mfa import MFAService
from app.services.rate_limit import LOGIN_LIMIT, REGISTRATION_LIMIT, enforce_rate_limit
from app.services.session import (
    clear_session_cookie,
    revoke_all_sessions,
    revoke_session,
)

router = APIRouter(prefix="/api/v1/auth", tags=["Identity"])


@router.post(
    "/register",
    response_model=MessageResponse,
    status_code=201,
    responses={409: {"model": MessageResponse}, 422: {"description": "Invalid registration"}},
)
async def register(
    body: RegisterRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    await enforce_rate_limit(REGISTRATION_LIMIT, client_ip(request))
    await IdentityService(db).register(
        request, str(body.email), body.password, body.name, body.accept_terms
    )
    await db.commit()
    return MessageResponse(message="Registration completed. Verify your email to continue.")


@router.post("/verify-email", response_model=MessageResponse)
async def verify_email(
    body: VerifyEmailRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    await IdentityService(db).verify_email(request, body.token)
    await db.commit()
    return MessageResponse(message="Email verified")


@router.post("/resend-verification", response_model=MessageResponse)
async def resend_verification(
    body: ResendVerificationRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    await enforce_rate_limit(REGISTRATION_LIMIT, f"resend:{client_ip(request)}")
    await IdentityService(db).resend_verification(request, str(body.email))
    await db.commit()
    return MessageResponse(
        message="If the account exists and is unverified, a verification email was sent."
    )


@router.post("/forgot-password", response_model=MessageResponse)
async def forgot_password(
    body: ForgotPasswordRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    await enforce_rate_limit(REGISTRATION_LIMIT, f"reset:{client_ip(request)}")
    await IdentityService(db).forgot_password(request, str(body.email))
    await db.commit()
    return MessageResponse(message="If the account exists, a password reset email was sent.")


@router.post("/reset-password", response_model=MessageResponse)
async def reset_password(
    body: ResetPasswordRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    await IdentityService(db).reset_password(request, body.token, body.new_password)
    await db.commit()
    return MessageResponse(message="Password reset completed")


@router.post("/login", response_model=LoginResponse)
async def login(
    body: LoginRequest,
    request: Request,
    response: Response,
    db: AsyncSession = Depends(get_db),
) -> LoginResponse:
    await enforce_rate_limit(LOGIN_LIMIT, client_ip(request))
    service = IdentityService(db)
    user = await service.verify_primary_credentials(request, str(body.email), body.password)
    if user.mfa_enabled:
        challenge = service.issue_mfa_challenge(user.id)
        await write_audit_log(
            db,
            request,
            "MFA_CHALLENGE_ISSUED",
            actor_id=user.id,
            subject_id=user.id,
        )
        await db.commit()
        return LoginResponse(mfa_required=True, challenge_token=challenge)
    await service.complete_login(request, response, user, mfa_completed=False)
    await db.commit()
    return LoginResponse(user_id=user.id, email=user.email)


@router.get("/me", response_model=UserResponse)
async def me(principal: Principal = Depends(current_principal)) -> UserResponse:
    user = principal.user
    return UserResponse(
        id=user.id,
        email=user.email,
        name=user.name,
        email_verified=user.email_verified,
        status=user.status,
        role=user.role,
        mfa_enabled=user.mfa_enabled,
        created_at=user.created_at,
    )


@router.post("/logout", response_model=MessageResponse)
async def logout(
    request: Request,
    response: Response,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    await revoke_session(db, response, principal.user.id, principal.session.id)
    await write_audit_log(
        db,
        request,
        "LOGOUT",
        actor_id=principal.user.id,
        subject_id=principal.user.id,
        details={"session_id": principal.session.id},
    )
    await db.commit()
    return MessageResponse(message="Logged out")


@router.post("/logout-all", response_model=MessageResponse)
async def logout_all(
    request: Request,
    response: Response,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    count = await revoke_all_sessions(db, principal.user.id)
    clear_session_cookie(response)
    await write_audit_log(
        db,
        request,
        "LOGOUT_ALL",
        actor_id=principal.user.id,
        subject_id=principal.user.id,
        details={"revoked_sessions": count},
    )
    await db.commit()
    return MessageResponse(message="All sessions were revoked")


@router.post("/step-up", response_model=MessageResponse)
async def step_up(
    body: StepUpRequest,
    request: Request,
    principal: Principal = Depends(current_principal),
    db: AsyncSession = Depends(get_db),
) -> MessageResponse:
    if not verify_password(body.password, principal.user.password_hash):
        await write_audit_log(
            db,
            request,
            "STEP_UP_FAILED",
            actor_id=principal.user.id,
            subject_id=principal.user.id,
        )
        await db.commit()
        raise AppError(401, "invalid_credentials", "Step-up authentication failed")
    await MFAService(db).verify_step_up(principal.user, body.code)
    principal.session.step_up_at = utcnow()
    await write_audit_log(
        db,
        request,
        "STEP_UP_COMPLETED",
        actor_id=principal.user.id,
        subject_id=principal.user.id,
    )
    await db.commit()
    return MessageResponse(message="Step-up authentication completed")
