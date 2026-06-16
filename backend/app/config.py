from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: str = "staging"
    issuer: str = "https://staging.api.ods.uz"
    account_url: str = "https://staging.account.ods.uz"
    api_url: str = "https://staging.api.ods.uz"

    database_url: str = "postgresql+asyncpg://ods:ods@postgres:5432/ods_sso"
    redis_url: str = "redis://redis:6379/0"

    keycloak_url: str = "http://keycloak:8080"
    keycloak_realm: str = "ods"
    keycloak_client_id: str = "ods-backend"
    keycloak_client_secret: str = "change_me_backend_secret"

    jwt_private_key: str = ""
    jwt_public_key: str = ""
    session_secret: str = "change_me_session_secret_min_32_chars"

    tatarlar_client_secret: str = ""

    access_token_ttl: int = 900
    id_token_ttl: int = 900
    refresh_token_ttl: int = 604800
    auth_code_ttl: int = 600


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
