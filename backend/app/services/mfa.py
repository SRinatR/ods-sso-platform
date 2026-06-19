import secrets

import pyotp
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import BackupCode, User, UserTwoFactorMethod
from app.errors import AppError
from app.security import (
    constant_time_secret_matches,
    decrypt_sensitive,
    encrypt_sensitive,
    get_jwt_service,
    hash_secret,
    utcnow,
)
from app.services.cache import get_cache


class MFAService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def setup_totp(self, user: User) -> tuple[str, str]:
        secret = pyotp.random_base32()
        await get_cache().set(
            f"mfa:totp:pending:{user.id}",
            encrypt_sensitive(secret, f"pending-totp:{user.id}"),
            600,
        )
        uri = pyotp.TOTP(secret).provisioning_uri(name=user.email, issuer_name="ODS Identity")
        return secret, uri

    async def enable_totp(self, user: User, code: str) -> list[str]:
        encrypted = await get_cache().get(f"mfa:totp:pending:{user.id}")
        if not encrypted:
            raise AppError(400, "totp_setup_expired", "TOTP setup has expired")
        secret = decrypt_sensitive(encrypted, f"pending-totp:{user.id}")
        if not pyotp.TOTP(secret).verify(code, valid_window=1):
            raise AppError(400, "invalid_otp", "One-time password is invalid")
        now = utcnow()
        result = await self.db.execute(
            select(UserTwoFactorMethod).where(
                UserTwoFactorMethod.user_id == user.id,
                UserTwoFactorMethod.method_type == "totp",
            )
        )
        method = result.scalar_one_or_none()
        encrypted_secret = encrypt_sensitive(secret, f"totp:{user.id}")
        if method:
            method.secret_encrypted = encrypted_secret
            method.enabled = True
            method.verified_at = now
        else:
            self.db.add(
                UserTwoFactorMethod(
                    user_id=user.id,
                    method_type="totp",
                    secret_encrypted=encrypted_secret,
                    enabled=True,
                    verified_at=now,
                )
            )
        user.mfa_enabled = True
        codes = await self.regenerate_backup_codes(user.id)
        await get_cache().delete(f"mfa:totp:pending:{user.id}")
        return codes

    async def regenerate_backup_codes(self, user_id: str) -> list[str]:
        await self.db.execute(delete(BackupCode).where(BackupCode.user_id == user_id))
        raw_codes = [f"{secrets.token_hex(4)}-{secrets.token_hex(4)}" for _ in range(10)]
        self.db.add_all(
            [BackupCode(user_id=user_id, code_hash=hash_secret(code)) for code in raw_codes]
        )
        await self.db.flush()
        return raw_codes

    async def verify_totp(self, user_id: str, code: str) -> bool:
        result = await self.db.execute(
            select(UserTwoFactorMethod).where(
                UserTwoFactorMethod.user_id == user_id,
                UserTwoFactorMethod.method_type == "totp",
                UserTwoFactorMethod.enabled.is_(True),
            )
        )
        method = result.scalar_one_or_none()
        if not method:
            return False
        secret = decrypt_sensitive(method.secret_encrypted, f"totp:{user_id}")
        return bool(pyotp.TOTP(secret).verify(code, valid_window=1))

    async def verify_backup_code(self, user_id: str, code: str) -> bool:
        result = await self.db.execute(select(BackupCode).where(BackupCode.user_id == user_id))
        candidates = list(result.scalars())
        matched: BackupCode | None = None
        for candidate in candidates:
            is_match = candidate.used_at is None and constant_time_secret_matches(
                code, candidate.code_hash
            )
            if is_match:
                matched = candidate
        if matched:
            matched.used_at = utcnow()
            return True
        return False

    async def verify_challenge(self, challenge_token: str, code: str, method: str) -> User:
        claims = get_jwt_service().decode(
            challenge_token, audience="ods-account", token_use="preauth"
        )
        if claims.get("purpose") != "login_mfa":
            raise AppError(401, "invalid_mfa_challenge", "MFA challenge is invalid")
        user = await self.db.get(User, claims["sub"])
        if not user or not user.mfa_enabled:
            raise AppError(401, "invalid_mfa_challenge", "MFA challenge is invalid")
        valid = (
            await self.verify_totp(user.id, code)
            if method == "totp"
            else await self.verify_backup_code(user.id, code)
        )
        if not valid:
            raise AppError(401, "invalid_otp", "Second factor is invalid")
        return user

    async def verify_step_up(self, user: User, code: str | None) -> None:
        if not user.mfa_enabled:
            raise AppError(403, "mfa_required", "MFA must be enabled for step-up")
        if not code or not await self.verify_totp(user.id, code):
            raise AppError(401, "invalid_otp", "Second factor is invalid")
