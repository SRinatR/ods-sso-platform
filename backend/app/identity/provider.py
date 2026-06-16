import hashlib
import secrets
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional


@dataclass
class IdentityUser:
    subject: str
    email: str
    email_verified: bool
    name: Optional[str] = None


class IdentityProvider(ABC):
    @abstractmethod
    async def authenticate(self, email: str, password: str) -> Optional[IdentityUser]:
        """Validate credentials and return identity user or None."""

    @abstractmethod
    async def create_user(
        self, email: str, password: str, name: Optional[str] = None
    ) -> IdentityUser:
        """Create user in identity core."""

    @abstractmethod
    async def get_user_by_subject(self, subject: str) -> Optional[IdentityUser]:
        """Fetch user from identity core by provider subject."""


def hash_client_secret(secret: str) -> str:
    return hashlib.sha256(secret.encode()).hexdigest()


def verify_client_secret(secret: str, secret_hash: str) -> bool:
    return hash_client_secret(secret) == secret_hash


def generate_client_secret() -> str:
    return secrets.token_urlsafe(32)
