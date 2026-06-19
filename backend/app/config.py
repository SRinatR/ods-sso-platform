from functools import lru_cache

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: str = "dev"
    issuer: str = "http://localhost:8080"
    account_url: str = "http://localhost:3000"
    api_url: str = "http://localhost:8080"

    database_url: str = "postgresql+asyncpg://ods:ods_dev_password@postgres:5432/ods_sso"
    redis_url: str = "redis://redis:6379/0"

    session_secret: str = Field(
        default="dev-session-secret-change-before-production", min_length=32
    )
    token_pepper: str = Field(default="dev-token-pepper-change-before-production", min_length=32)
    totp_encryption_key: str = ""
    jwt_private_key: str = ""
    jwt_public_key: str = ""
    jwt_key_id: str = "ods-platform-1"

    access_token_ttl: int = 900
    id_token_ttl: int = 900
    refresh_token_ttl: int = 2_592_000
    session_ttl: int = 2_592_000
    auth_code_ttl: int = 300
    oauth_request_ttl: int = 600
    verification_token_ttl: int = 86_400
    password_reset_token_ttl: int = 3_600
    preauth_token_ttl: int = 300
    step_up_ttl: int = 600

    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_starttls: bool = True
    mail_from: str = "no-reply@ods.uz"

    bootstrap_admin_email: str = ""
    bootstrap_admin_password: str = ""
    tatarlar_client_secret: str = ""

    trusted_proxy_count: int = 0
    allowed_origins: str = ""

    @field_validator("env")
    @classmethod
    def validate_env(cls, value: str) -> str:
        allowed = {"dev", "test", "staging", "production"}
        if value not in allowed:
            raise ValueError(f"ENV must be one of {sorted(allowed)}")
        return value

    @property
    def cors_origins(self) -> list[str]:
        configured = [item.strip() for item in self.allowed_origins.split(",") if item.strip()]
        return list(dict.fromkeys([self.account_url, *configured]))

    @property
    def secure_cookies(self) -> bool:
        return self.env in {"staging", "production"}


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
