from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator


class ErrorResponse(BaseModel):
    error: str
    message: str
    details: list[dict[str, Any]]
    request_id: str


class MessageResponse(BaseModel):
    ok: bool = True
    message: str


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=128)
    name: str = Field(min_length=1, max_length=255)
    accept_terms: bool


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class VerifyEmailRequest(BaseModel):
    token: str = Field(min_length=20, max_length=512)


class ResendVerificationRequest(BaseModel):
    email: EmailStr


class ForgotPasswordRequest(BaseModel):
    email: EmailStr


class ResetPasswordRequest(BaseModel):
    token: str = Field(min_length=20, max_length=512)
    new_password: str = Field(min_length=12, max_length=128)


class MFAChallengeRequest(BaseModel):
    challenge_token: str
    code: str = Field(min_length=6, max_length=32)
    method: Literal["totp", "backup"] = "totp"


class StepUpRequest(BaseModel):
    password: str = Field(min_length=1, max_length=128)
    code: str | None = Field(default=None, min_length=6, max_length=32)


class LoginResponse(BaseModel):
    ok: bool = True
    user_id: str | None = None
    email: EmailStr | None = None
    mfa_required: bool = False
    challenge_token: str | None = None


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    email: EmailStr
    name: str | None
    email_verified: bool
    status: str
    role: str
    mfa_enabled: bool
    created_at: datetime


class SessionResponse(BaseModel):
    id: str
    ip_address: str | None
    user_agent: str | None
    created_at: datetime
    last_seen_at: datetime
    expires_at: datetime
    current: bool
    mfa_completed: bool
    step_up_valid: bool


class LoginHistoryResponse(BaseModel):
    id: str
    email: EmailStr
    success: bool
    failure_reason: str | None
    ip_address: str | None
    user_agent: str | None
    created_at: datetime


class TOTPSetupResponse(BaseModel):
    secret: str
    provisioning_uri: str
    expires_in: int


class TOTPEnableRequest(BaseModel):
    code: str = Field(pattern=r"^\d{6}$")


class BackupCodesResponse(BaseModel):
    backup_codes: list[str]


class OAuthClientCreate(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    description: str | None = Field(default=None, max_length=2000)
    redirect_uris: list[str] = Field(min_length=1, max_length=20)
    allowed_scopes: list[str] = Field(default=["openid", "profile", "email"])
    is_public: bool = False
    token_endpoint_auth_method: Literal["client_secret_basic", "client_secret_post", "none"] = (
        "client_secret_basic"
    )

    @field_validator("redirect_uris")
    @classmethod
    def validate_redirect_uris(cls, values: list[str]) -> list[str]:
        for value in values:
            if not value.startswith(("https://", "http://localhost", "http://127.0.0.1")):
                raise ValueError("Redirect URI must use HTTPS except for loopback development URIs")
            if "#" in value:
                raise ValueError("Redirect URI must not contain a fragment")
        return list(dict.fromkeys(values))


class OAuthClientUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=255)
    description: str | None = Field(default=None, max_length=2000)
    redirect_uris: list[str] | None = None
    allowed_scopes: list[str] | None = None
    enabled: bool | None = None


class OAuthClientResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    client_id: str
    name: str
    description: str | None
    redirect_uris: list[str]
    allowed_scopes: list[str]
    grant_types: list[str]
    token_endpoint_auth_method: str
    is_public: bool
    require_pkce: bool
    enabled: bool
    created_at: datetime


class OAuthClientCreatedResponse(OAuthClientResponse):
    client_secret: str | None


class ConsentDecisionRequest(BaseModel):
    request_id: str


class ConnectedApplicationResponse(BaseModel):
    consent_id: str
    client_id: str
    name: str
    scopes: list[str]
    granted_at: datetime


class OAuthConsentDetailsResponse(BaseModel):
    request_id: str
    client_id: str
    client_name: str
    client_description: str | None
    requested_scopes: list[str]
    new_scopes: list[str]


class OAuthTokenResponse(BaseModel):
    access_token: str
    id_token: str
    token_type: Literal["Bearer"]
    expires_in: int
    refresh_token: str
    scope: str


class OAuthIntrospectionResponse(BaseModel):
    active: bool
    sub: str | None = None
    client_id: str | None = None
    scope: str | None = None
    exp: int | None = None
    iat: int | None = None
    token_type: str | None = None


class UserInfoResponse(BaseModel):
    sub: str
    email: EmailStr | None = None
    email_verified: bool | None = None
    name: str | None = None


class AdminDashboardResponse(BaseModel):
    users_total: int
    users_active: int
    active_sessions: int
    oauth_clients: int
    failed_logins_24h: int
    audit_events_24h: int


class AdminUserUpdate(BaseModel):
    status: Literal["active", "suspended", "disabled"] | None = None
    role: Literal["user", "support", "auditor", "security_admin", "admin"] | None = None


class AuditResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    event_type: str
    actor_id: str | None
    subject_id: str | None
    client_id: str | None
    request_id: str
    ip_address: str | None
    details: dict[str, Any]
    created_at: datetime


class SecurityPolicyResponse(BaseModel):
    key: str
    value: dict[str, Any]
    updated_by: str | None
    updated_at: datetime


class SecurityPolicyUpdate(BaseModel):
    value: dict[str, Any]
