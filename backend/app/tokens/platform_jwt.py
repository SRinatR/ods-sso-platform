import json
import secrets
import time
from datetime import datetime, timezone
from typing import Any, Optional

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from jose import jwt

from app.config import settings


class PlatformJWTService:
    def __init__(self, private_key_pem: str, public_key_pem: str):
        self.private_key = private_key_pem
        self.public_key = public_key_pem
        self.kid = "ods-platform-1"

    @classmethod
    def from_settings(cls) -> "PlatformJWTService":
        private_pem, public_pem = _ensure_keys()
        return cls(private_pem, public_pem)

    def jwks(self) -> dict:
        from cryptography.hazmat.primitives.serialization import load_pem_public_key

        pub = load_pem_public_key(self.public_key.encode())
        numbers = pub.public_numbers()
        import base64

        def b64url_uint(val: int) -> str:
            b = val.to_bytes((val.bit_length() + 7) // 8, "big")
            return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

        return {
            "keys": [
                {
                    "kty": "RSA",
                    "use": "sig",
                    "alg": "RS256",
                    "kid": self.kid,
                    "n": b64url_uint(numbers.n),
                    "e": b64url_uint(numbers.e),
                }
            ]
        }

    def issue_access_token(
        self,
        user_id: str,
        client_id: str,
        email: str,
        scope: str,
        email_verified: bool = False,
        name: Optional[str] = None,
    ) -> str:
        now = int(time.time())
        claims: dict[str, Any] = {
            "iss": settings.issuer,
            "sub": user_id,
            "aud": client_id,
            "email": email,
            "email_verified": email_verified,
            "scope": scope,
            "client_id": client_id,
            "iat": now,
            "exp": now + settings.access_token_ttl,
            "token_use": "access",
        }
        if name:
            claims["name"] = name
        return jwt.encode(claims, self.private_key, algorithm="RS256", headers={"kid": self.kid})

    def issue_id_token(
        self,
        user_id: str,
        client_id: str,
        email: str,
        email_verified: bool = False,
        name: Optional[str] = None,
        nonce: Optional[str] = None,
    ) -> str:
        now = int(time.time())
        claims: dict[str, Any] = {
            "iss": settings.issuer,
            "sub": user_id,
            "aud": client_id,
            "email": email,
            "email_verified": email_verified,
            "iat": now,
            "exp": now + settings.id_token_ttl,
            "auth_time": now,
        }
        if name:
            claims["name"] = name
        if nonce:
            claims["nonce"] = nonce
        return jwt.encode(claims, self.private_key, algorithm="RS256", headers={"kid": self.kid})

    def issue_refresh_token(self, user_id: str, client_id: str) -> str:
        now = int(time.time())
        claims = {
            "iss": settings.issuer,
            "sub": user_id,
            "aud": client_id,
            "iat": now,
            "exp": now + settings.refresh_token_ttl,
            "token_use": "refresh",
            "jti": secrets.token_urlsafe(16),
        }
        return jwt.encode(claims, self.private_key, algorithm="RS256", headers={"kid": self.kid})

    def decode_token(self, token: str, audience: Optional[str] = None) -> dict:
        options = {"verify_aud": audience is not None}
        return jwt.decode(
            token,
            self.public_key,
            algorithms=["RS256"],
            audience=audience,
            issuer=settings.issuer,
            options=options,
        )


_jwt_service: Optional[PlatformJWTService] = None


def get_jwt_service() -> PlatformJWTService:
    global _jwt_service
    if _jwt_service is None:
        _jwt_service = PlatformJWTService.from_settings()
    return _jwt_service


def _ensure_keys() -> tuple[str, str]:
    if settings.jwt_private_key and settings.jwt_public_key:
        private_pem = settings.jwt_private_key.replace("\\n", "\n")
        public_pem = settings.jwt_public_key.replace("\\n", "\n")
        return private_pem, public_pem

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode()
    public_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return private_pem, public_pem
