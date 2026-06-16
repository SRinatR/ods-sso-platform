import logging

from sqlalchemy import select

from app.config import settings
from app.db.database import AsyncSessionLocal
from app.db.models import OAuthClient, User
from app.identity.provider import generate_client_secret, hash_client_secret

logger = logging.getLogger(__name__)

TATARLAR_CLIENT_ID = "ods_tatarlar_staging"
TATARLAR_REDIRECT_URIS = "\n".join(
    [
        "https://api-staging.tatarlar.uz/api/v1/auth/sso/callback",
        "http://localhost:3002/api/v1/auth/sso/callback",
        "https://api.tatarlar.uz/api/v1/auth/sso/callback",
    ]
)

PILOT_USERS = [
    ("pilot-admin@ods.uz", "Pilot Admin"),
    ("pilot-member@ods.uz", "Pilot Member"),
]


async def run_seed() -> None:
    async with AsyncSessionLocal() as db:
        await _seed_tatarlar_client(db)
        await _sync_pilot_users(db)
        await db.commit()


async def _seed_tatarlar_client(db) -> None:
    result = await db.execute(select(OAuthClient).where(OAuthClient.client_id == TATARLAR_CLIENT_ID))
    existing = result.scalar_one_or_none()
    if existing:
        return

    secret = settings.tatarlar_client_secret or generate_client_secret()
    client = OAuthClient(
        client_id=TATARLAR_CLIENT_ID,
        client_secret_hash=hash_client_secret(secret),
        name="Tatarlar Platform (staging)",
        redirect_uris=TATARLAR_REDIRECT_URIS,
        grant_types="authorization_code,refresh_token",
        scopes="openid email profile",
        require_pkce=True,
        token_endpoint_auth="client_secret_post",
        enabled=True,
    )
    db.add(client)
    logger.info("Seeded OAuth client %s", TATARLAR_CLIENT_ID)
    if not settings.tatarlar_client_secret:
        logger.warning(
            "TATARLAR_CLIENT_SECRET not set — generated secret (check logs on first boot): %s",
            secret,
        )
        print(f"\n=== TATARLAR STAGING CREDENTIALS ===\nclient_id: {TATARLAR_CLIENT_ID}\nclient_secret: {secret}\n=====================================\n")


async def _sync_pilot_users(db) -> None:
    import os

    import httpx

    from app.config import settings as s

    for email, name in PILOT_USERS:
        result = await db.execute(select(User).where(User.email == email))
        if result.scalar_one_or_none():
            continue
        async with httpx.AsyncClient(timeout=15) as client:
            token_resp = await client.post(
                f"{s.keycloak_url}/realms/master/protocol/openid-connect/token",
                data={
                    "grant_type": "password",
                    "client_id": "admin-cli",
                    "username": os.environ.get("KEYCLOAK_ADMIN", "admin"),
                    "password": os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "admin"),
                },
            )
            if token_resp.status_code != 200:
                logger.warning("Could not sync pilot users — Keycloak admin unavailable")
                return
            token = token_resp.json()["access_token"]
            search = await client.get(
                f"{s.keycloak_url}/admin/realms/{s.keycloak_realm}/users",
                params={"email": email, "exact": "true"},
                headers={"Authorization": f"Bearer {token}"},
            )
            if search.status_code != 200 or not search.json():
                logger.warning("Pilot user %s not found in Keycloak", email)
                continue
            kc_user = search.json()[0]
            user = User(
                email=email,
                email_verified=kc_user.get("emailVerified", True),
                name=name,
                identity_provider_id=kc_user["id"],
            )
            db.add(user)
            logger.info("Synced pilot user %s -> %s", email, user.id)
