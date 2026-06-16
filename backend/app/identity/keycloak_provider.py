from typing import Optional

import httpx

from app.config import settings
from app.identity.provider import IdentityProvider, IdentityUser


class KeycloakProvider(IdentityProvider):
    """Only module allowed to talk to Keycloak directly (ADR-001)."""

    def __init__(self) -> None:
        self.base = f"{settings.keycloak_url}/realms/{settings.keycloak_realm}"
        self.admin_base = f"{settings.keycloak_url}/admin/realms/{settings.keycloak_realm}"
        self._admin_token: Optional[str] = None

    async def _get_admin_token(self) -> str:
        if self._admin_token:
            return self._admin_token
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                f"{settings.keycloak_url}/realms/master/protocol/openid-connect/token",
                data={
                    "grant_type": "client_credentials",
                    "client_id": "admin-cli",
                    "username": "admin",
                    "password": settings.keycloak_client_secret,
                },
            )
            # Fallback: use master admin password from env via password grant
            if resp.status_code != 200:
                import os

                resp = await client.post(
                    f"{settings.keycloak_url}/realms/master/protocol/openid-connect/token",
                    data={
                        "grant_type": "password",
                        "client_id": "admin-cli",
                        "username": os.environ.get("KEYCLOAK_ADMIN", "admin"),
                        "password": os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "admin"),
                    },
                )
            resp.raise_for_status()
            self._admin_token = resp.json()["access_token"]
            return self._admin_token

    async def authenticate(self, email: str, password: str) -> Optional[IdentityUser]:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                f"{self.base}/protocol/openid-connect/token",
                data={
                    "grant_type": "password",
                    "client_id": settings.keycloak_client_id,
                    "client_secret": settings.keycloak_client_secret,
                    "username": email,
                    "password": password,
                    "scope": "openid email profile",
                },
            )
            if resp.status_code != 200:
                return None
            data = resp.json()
            userinfo = await self._fetch_userinfo(client, data["access_token"])
            return IdentityUser(
                subject=userinfo["sub"],
                email=userinfo.get("email", email),
                email_verified=userinfo.get("email_verified", False),
                name=userinfo.get("name") or userinfo.get("preferred_username"),
            )

    async def _fetch_userinfo(self, client: httpx.AsyncClient, access_token: str) -> dict:
        resp = await client.get(
            f"{self.base}/protocol/openid-connect/userinfo",
            headers={"Authorization": f"Bearer {access_token}"},
        )
        resp.raise_for_status()
        return resp.json()

    async def create_user(
        self, email: str, password: str, name: Optional[str] = None
    ) -> IdentityUser:
        first_name = (name or email.split("@")[0]).split()[0]
        last_name = ""
        if name and " " in name:
            last_name = name.split(" ", 1)[1]

        token = await self._get_admin_token()
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                f"{self.admin_base}/users",
                headers={"Authorization": f"Bearer {token}"},
                json={
                    "username": email,
                    "email": email,
                    "emailVerified": False,
                    "enabled": True,
                    "firstName": first_name,
                    "lastName": last_name,
                    "credentials": [{"type": "password", "value": password, "temporary": False}],
                },
            )
            if resp.status_code not in (201, 409):
                resp.raise_for_status()

            # Find user id
            search = await client.get(
                f"{self.admin_base}/users",
                params={"email": email, "exact": "true"},
                headers={"Authorization": f"Bearer {token}"},
            )
            search.raise_for_status()
            users = search.json()
            if not users:
                raise RuntimeError("User creation failed")
            u = users[0]
            full_name = f"{u.get('firstName', '')} {u.get('lastName', '')}".strip() or email
            return IdentityUser(
                subject=u["id"],
                email=email,
                email_verified=u.get("emailVerified", False),
                name=full_name,
            )

    async def get_user_by_subject(self, subject: str) -> Optional[IdentityUser]:
        token = await self._get_admin_token()
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.get(
                f"{self.admin_base}/users/{subject}",
                headers={"Authorization": f"Bearer {token}"},
            )
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            u = resp.json()
            full_name = f"{u.get('firstName', '')} {u.get('lastName', '')}".strip()
            return IdentityUser(
                subject=u["id"],
                email=u.get("email", ""),
                email_verified=u.get("emailVerified", False),
                name=full_name or u.get("username"),
            )


def get_identity_provider() -> IdentityProvider:
    return KeycloakProvider()
