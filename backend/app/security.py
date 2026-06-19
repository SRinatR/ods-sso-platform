import base64
import hashlib
import hmac
import json
import secrets
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey, RSAPublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from jose import JWTError, jwt

from app.config import settings
from app.errors import AppError

PASSWORD_HASHER = PasswordHasher(
    time_cost=1 if settings.env == "test" else 3,
    memory_cost=8_192 if settings.env == "test" else 65_536,
    parallelism=1 if settings.env == "test" else 4,
    hash_len=32,
    salt_len=16,
)


def utcnow() -> datetime:
    return datetime.now(UTC)


def as_utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def hash_password(password: str) -> str:
    if len(password) > 128:
        raise AppError(422, "password_too_long", "Password must not exceed 128 characters")
    return PASSWORD_HASHER.hash(password)


def verify_password(password: str, encoded: str) -> bool:
    if len(password) > 128:
        return False
    try:
        return PASSWORD_HASHER.verify(encoded, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


def hash_secret(secret: str) -> str:
    return hmac.new(
        settings.token_pepper.encode("utf-8"), secret.encode("utf-8"), hashlib.sha256
    ).hexdigest()


def constant_time_secret_matches(secret: str, expected_hash: str) -> bool:
    return hmac.compare_digest(hash_secret(secret), expected_hash)


def issue_opaque_token(prefix: str) -> tuple[str, str, str]:
    token_id = f"{prefix}_{secrets.token_urlsafe(18)}"
    secret = secrets.token_urlsafe(32)
    return token_id, secret, f"{token_id}.{secret}"


def split_opaque_token(token: str, prefix: str) -> tuple[str, str]:
    token_id, separator, secret = token.partition(".")
    if not separator or not token_id.startswith(f"{prefix}_") or not secret:
        raise AppError(400, "invalid_token", "Token format is invalid")
    return token_id, secret


def _encryption_key() -> bytes:
    if settings.totp_encryption_key:
        try:
            key = base64.urlsafe_b64decode(settings.totp_encryption_key.encode("ascii"))
        except ValueError as exc:
            raise RuntimeError("TOTP_ENCRYPTION_KEY must be URL-safe base64") from exc
    elif settings.env in {"dev", "test"}:
        key = hashlib.sha256(settings.session_secret.encode("utf-8")).digest()
    else:
        raise RuntimeError("TOTP_ENCRYPTION_KEY is required outside development")
    if len(key) != 32:
        raise RuntimeError("TOTP_ENCRYPTION_KEY must decode to exactly 32 bytes")
    return key


def encrypt_sensitive(value: str, context: str) -> str:
    nonce = secrets.token_bytes(12)
    ciphertext = AESGCM(_encryption_key()).encrypt(
        nonce, value.encode("utf-8"), context.encode("utf-8")
    )
    return base64.urlsafe_b64encode(nonce + ciphertext).decode("ascii")


def decrypt_sensitive(value: str, context: str) -> str:
    payload = base64.urlsafe_b64decode(value.encode("ascii"))
    plaintext = AESGCM(_encryption_key()).decrypt(
        payload[:12], payload[12:], context.encode("utf-8")
    )
    return plaintext.decode("utf-8")


class JWTService:
    def __init__(self) -> None:
        self.private_key, self.public_key = self._keys()
        self.kid = settings.jwt_key_id

    @staticmethod
    def _keys() -> tuple[str, str]:
        if bool(settings.jwt_private_key) != bool(settings.jwt_public_key):
            raise RuntimeError("JWT_PRIVATE_KEY and JWT_PUBLIC_KEY must be configured together")
        if settings.jwt_private_key and settings.jwt_public_key:
            private_pem = settings.jwt_private_key.replace("\\n", "\n")
            public_pem = settings.jwt_public_key.replace("\\n", "\n")
            try:
                private_key = serialization.load_pem_private_key(
                    private_pem.encode("ascii"),
                    password=None,
                )
                public_key = serialization.load_pem_public_key(public_pem.encode("ascii"))
            except (TypeError, ValueError) as exc:
                raise RuntimeError("Configured JWT keys are not valid PEM keys") from exc
            if not isinstance(private_key, RSAPrivateKey) or not isinstance(
                public_key, RSAPublicKey
            ):
                raise RuntimeError("Configured JWT keys must be RSA keys")
            if private_key.public_key().public_numbers() != public_key.public_numbers():
                raise RuntimeError("Configured JWT public key does not match the private key")
            return private_pem, public_pem
        if settings.env not in {"dev", "test"}:
            raise RuntimeError("JWT_PRIVATE_KEY and JWT_PUBLIC_KEY are required")
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        private_pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ).decode("ascii")
        public_pem = (
            key.public_key()
            .public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
            .decode("ascii")
        )
        return private_pem, public_pem

    def issue(
        self,
        subject: str,
        audience: str,
        token_use: str,
        ttl: int,
        claims: dict[str, Any] | None = None,
    ) -> tuple[str, dict[str, Any]]:
        now = utcnow()
        payload: dict[str, Any] = {
            "iss": settings.issuer,
            "sub": subject,
            "aud": audience,
            "iat": int(now.timestamp()),
            "exp": int((now + timedelta(seconds=ttl)).timestamp()),
            "jti": secrets.token_urlsafe(18),
            "token_use": token_use,
        }
        payload.update(claims or {})
        encoded = jwt.encode(
            payload, self.private_key, algorithm="RS256", headers={"kid": self.kid}
        )
        return encoded, payload

    def decode(
        self,
        token: str,
        audience: str | None = None,
        token_use: str | None = None,
    ) -> dict[str, Any]:
        try:
            claims = jwt.decode(
                token,
                self.public_key,
                algorithms=["RS256"],
                issuer=settings.issuer,
                audience=audience,
                options={"verify_aud": audience is not None},
            )
        except JWTError as exc:
            raise AppError(401, "invalid_token", "Token is invalid or expired") from exc
        if token_use and claims.get("token_use") != token_use:
            raise AppError(401, "invalid_token", "Token has an invalid use")
        return claims

    def jwks(self) -> dict[str, Any]:
        public_key = serialization.load_pem_public_key(self.public_key.encode("ascii"))
        if not isinstance(public_key, RSAPublicKey):
            raise RuntimeError("Configured JWT public key is not RSA")
        numbers = public_key.public_numbers()

        def encode_number(number: int) -> str:
            raw = number.to_bytes((number.bit_length() + 7) // 8, "big")
            return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

        return {
            "keys": [
                {
                    "kty": "RSA",
                    "use": "sig",
                    "alg": "RS256",
                    "kid": self.kid,
                    "n": encode_number(numbers.n),
                    "e": encode_number(numbers.e),
                }
            ]
        }


@lru_cache
def get_jwt_service() -> JWTService:
    return JWTService()


def validate_runtime_security() -> None:
    if settings.env not in {"staging", "production"}:
        get_jwt_service()
        return
    missing = [
        name
        for name, value in {
            "SESSION_SECRET": settings.session_secret,
            "TOKEN_PEPPER": settings.token_pepper,
            "TOTP_ENCRYPTION_KEY": settings.totp_encryption_key,
            "JWT_PRIVATE_KEY": settings.jwt_private_key,
            "JWT_PUBLIC_KEY": settings.jwt_public_key,
            "SMTP_HOST": settings.smtp_host,
        }.items()
        if not value
    ]
    if missing:
        raise RuntimeError(f"Missing required security configuration: {', '.join(missing)}")
    if settings.session_secret == settings.token_pepper:
        raise RuntimeError("SESSION_SECRET and TOKEN_PEPPER must be independent")
    if "change-before-production" in settings.session_secret or "change-before-production" in (
        settings.token_pepper
    ):
        raise RuntimeError("Development secrets cannot be used outside development")
    for name, value in {
        "ISSUER": settings.issuer,
        "ACCOUNT_URL": settings.account_url,
        "API_URL": settings.api_url,
    }.items():
        if not value.startswith("https://"):
            raise RuntimeError(f"{name} must use HTTPS outside development")
    _encryption_key()
    get_jwt_service()


SENSITIVE_KEYS = {
    "password",
    "password_hash",
    "access_token",
    "refresh_token",
    "authorization_code",
    "otp",
    "totp",
    "backup_code",
    "client_secret",
    "private_key",
}


def sanitize_for_log(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: "[redacted]" if key.lower() in SENSITIVE_KEYS else sanitize_for_log(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [sanitize_for_log(item) for item in value]
    return value


def canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
