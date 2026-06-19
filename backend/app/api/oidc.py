from fastapi import APIRouter

from app.config import settings
from app.security import get_jwt_service

router = APIRouter(tags=["OpenID Connect metadata"])


@router.get("/.well-known/openid-configuration")
async def openid_configuration() -> dict[str, object]:
    issuer = settings.issuer.rstrip("/")
    return {
        "issuer": issuer,
        "authorization_endpoint": f"{issuer}/authorize",
        "token_endpoint": f"{issuer}/token",
        "userinfo_endpoint": f"{issuer}/userinfo",
        "revocation_endpoint": f"{issuer}/revoke",
        "introspection_endpoint": f"{issuer}/introspect",
        "jwks_uri": f"{issuer}/.well-known/jwks.json",
        "response_types_supported": ["code"],
        "grant_types_supported": ["authorization_code", "refresh_token"],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "token_endpoint_auth_methods_supported": [
            "client_secret_basic",
            "client_secret_post",
            "none",
        ],
        "code_challenge_methods_supported": ["S256"],
        "scopes_supported": ["openid", "profile", "email", "offline_access"],
        "claims_supported": [
            "sub",
            "name",
            "email",
            "email_verified",
            "iss",
            "aud",
            "exp",
            "iat",
            "auth_time",
            "nonce",
            "acr",
            "amr",
        ],
    }


@router.get("/.well-known/jwks.json")
async def jwks() -> dict[str, object]:
    return get_jwt_service().jwks()
