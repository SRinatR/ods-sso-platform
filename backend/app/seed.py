from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import AsyncSessionLocal
from app.db.models import ConsentVersion, OAuthClient, SecurityPolicy, User
from app.security import hash_password

DEFAULT_POLICIES = {
    "password": {
        "minimum_length": 12,
        "maximum_length": 128,
        "argon2id": True,
    },
    "login_protection": {
        "maximum_failed_attempts": 5,
        "lock_seconds": 900,
    },
    "admin": {
        "mfa_required": True,
        "step_up_seconds": 600,
    },
    "oauth": {
        "pkce_method": "S256",
        "access_token_seconds": 900,
        "refresh_rotation": True,
        "reuse_detection": True,
    },
}


async def run_seed() -> None:
    async with AsyncSessionLocal() as db:
        await seed_consent_version(db)
        await seed_policies(db)
        await seed_bootstrap_admin(db)
        await seed_tatarlar_client(db)
        await db.commit()


async def seed_consent_version(db: AsyncSession) -> None:
    existing = (
        await db.execute(
            select(ConsentVersion).where(
                ConsentVersion.version == "1.0", ConsentVersion.locale == "ru"
            )
        )
    ).scalar_one_or_none()
    if not existing:
        db.add(
            ConsentVersion(
                version="1.0",
                locale="ru",
                title="Доступ приложения",
                body=(
                    "Приложение получает только перечисленные разрешения. "
                    "Доступ можно полностью отозвать в разделе подключенных приложений."
                ),
                active=True,
            )
        )


async def seed_policies(db: AsyncSession) -> None:
    for key, value in DEFAULT_POLICIES.items():
        if not await db.get(SecurityPolicy, key):
            db.add(SecurityPolicy(key=key, value=value))


async def seed_bootstrap_admin(db: AsyncSession) -> None:
    if not settings.bootstrap_admin_email or not settings.bootstrap_admin_password:
        return
    email = settings.bootstrap_admin_email.lower()
    existing = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    if existing:
        return
    from app.security import utcnow

    db.add(
        User(
            email=email,
            password_hash=hash_password(settings.bootstrap_admin_password),
            name="Platform Administrator",
            role="admin",
            status="active",
            email_verified_at=utcnow(),
        )
    )


async def seed_tatarlar_client(db: AsyncSession) -> None:
    if not settings.tatarlar_client_secret:
        return
    client_id = "ods_tatarlar_staging"
    existing = (
        await db.execute(select(OAuthClient).where(OAuthClient.client_id == client_id))
    ).scalar_one_or_none()
    if existing:
        return
    db.add(
        OAuthClient(
            client_id=client_id,
            client_secret_hash=hash_password(settings.tatarlar_client_secret),
            name="Tatarlar Platform",
            description="Staging and production OIDC integration",
            redirect_uris=[
                "https://api-staging.tatarlar.uz/api/v1/auth/sso/callback",
                "http://localhost:3002/api/v1/auth/sso/callback",
                "https://api.tatarlar.uz/api/v1/auth/sso/callback",
            ],
            allowed_scopes=["openid", "profile", "email", "offline_access"],
            grant_types=["authorization_code", "refresh_token"],
            token_endpoint_auth_method="client_secret_post",
            is_public=False,
            require_pkce=True,
            enabled=True,
        )
    )
