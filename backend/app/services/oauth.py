import base64
import hashlib
import secrets
from datetime import datetime, timedelta
from urllib.parse import urlencode

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.models import (
    AuthorizationCode,
    OAuthAccessToken,
    OAuthAuthorizationRequest,
    OAuthClient,
    RefreshToken,
    Session,
    User,
)
from app.errors import AppError
from app.repositories.oauth import OAuthRepository
from app.repositories.sessions import SessionRepository
from app.repositories.users import UserRepository
from app.security import (
    as_utc,
    constant_time_secret_matches,
    get_jwt_service,
    hash_password,
    hash_secret,
    issue_opaque_token,
    split_opaque_token,
    utcnow,
    verify_password,
)
from app.services.audit import write_audit_log
from app.services.consent import ConsentService

SUPPORTED_SCOPES = {"openid", "profile", "email", "offline_access"}


def verify_pkce_s256(code_verifier: str, code_challenge: str) -> bool:
    digest = hashlib.sha256(code_verifier.encode("ascii")).digest()
    computed = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return secrets.compare_digest(computed, code_challenge)


def parse_scope(scope: str) -> list[str]:
    scopes = list(dict.fromkeys(part for part in scope.split() if part))
    if "openid" not in scopes:
        raise AppError(400, "invalid_scope", "The openid scope is required")
    if not set(scopes).issubset(SUPPORTED_SCOPES):
        raise AppError(400, "invalid_scope", "One or more requested scopes are unsupported")
    return scopes


def callback_url(redirect_uri: str, params: dict[str, str]) -> str:
    separator = "&" if "?" in redirect_uri else "?"
    return f"{redirect_uri}{separator}{urlencode(params)}"


class OAuthService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db
        self.repo = OAuthRepository(db)
        self.consents = ConsentService(db)

    async def validate_authorization_request(
        self,
        client_id: str,
        redirect_uri: str,
        response_type: str,
        scope: str,
        state: str | None,
        nonce: str | None,
        code_challenge: str | None,
        code_challenge_method: str | None,
    ) -> tuple[OAuthClient, list[str]]:
        client = await self.repo.get_client(client_id)
        if not client or not client.enabled:
            raise AppError(400, "invalid_client", "OAuth client is invalid")
        if redirect_uri not in client.redirect_uris:
            raise AppError(400, "invalid_redirect_uri", "Redirect URI is not registered")
        if response_type != "code":
            raise AppError(400, "unsupported_response_type", "Only authorization code is supported")
        if not state:
            raise AppError(400, "invalid_request", "State is required")
        if not nonce:
            raise AppError(400, "invalid_request", "Nonce is required for OpenID Connect")
        if code_challenge_method != "S256" or not code_challenge:
            raise AppError(400, "invalid_request", "PKCE S256 is required")
        if len(code_challenge) < 43 or len(code_challenge) > 128:
            raise AppError(400, "invalid_request", "PKCE code challenge length is invalid")
        scopes = parse_scope(scope)
        if not set(scopes).issubset(set(client.allowed_scopes)):
            raise AppError(400, "invalid_scope", "Client is not allowed to request these scopes")
        return client, scopes

    async def begin_authorization(
        self,
        request: Request,
        user: User,
        session: Session,
        client: OAuthClient,
        redirect_uri: str,
        scopes: list[str],
        state: str,
        nonce: str,
        code_challenge: str,
        prompt: str | None,
    ) -> tuple[str, bool]:
        authorization_request = OAuthAuthorizationRequest(
            user_id=user.id,
            session_id=session.id,
            client_id=client.client_id,
            redirect_uri=redirect_uri,
            scope=" ".join(scopes),
            state=state,
            nonce=nonce,
            code_challenge=code_challenge,
            prompt=prompt,
            expires_at=utcnow() + timedelta(seconds=settings.oauth_request_ttl),
        )
        await self.repo.add_request(authorization_request)
        missing_scopes = await self.consents.required_scopes(
            user.id, client.client_id, scopes, force=prompt == "consent"
        )
        await write_audit_log(
            self.db,
            request,
            "OAUTH_AUTHORIZE_STARTED",
            actor_id=user.id,
            subject_id=user.id,
            client_id=client.client_id,
            details={"scopes": scopes},
        )
        if missing_scopes:
            return authorization_request.id, True
        return await self.approve_authorization(request, user, authorization_request.id), False

    async def approve_authorization(
        self,
        request: Request,
        user: User,
        request_id: str,
    ) -> str:
        item = await self.repo.get_request(request_id)
        now = utcnow()
        if (
            not item
            or item.user_id != user.id
            or item.status != "pending"
            or as_utc(item.expires_at) <= now
            or not item.session_id
        ):
            raise AppError(400, "invalid_authorization_request", "Authorization request is invalid")
        scopes = parse_scope(item.scope)
        await self.consents.grant(request, user, item.client_id, scopes)
        code_id, secret, raw_code = issue_opaque_token("cod")
        code = AuthorizationCode(
            id=code_id,
            secret_hash=hash_secret(secret),
            user_id=user.id,
            session_id=item.session_id,
            client_id=item.client_id,
            redirect_uri=item.redirect_uri,
            scope=item.scope,
            code_challenge=item.code_challenge,
            nonce=item.nonce,
            auth_time=now,
            expires_at=now + timedelta(seconds=settings.auth_code_ttl),
        )
        await self.repo.add_code(code)
        item.status = "approved"
        await write_audit_log(
            self.db,
            request,
            "OAUTH_CODE_ISSUED",
            actor_id=user.id,
            subject_id=user.id,
            client_id=item.client_id,
            details={"request_id": item.id, "scopes": scopes},
        )
        return callback_url(item.redirect_uri, {"code": raw_code, "state": item.state or ""})

    async def deny_authorization(self, request: Request, user: User, request_id: str) -> str:
        item = await self.repo.get_request(request_id)
        if not item or item.user_id != user.id or item.status != "pending":
            raise AppError(400, "invalid_authorization_request", "Authorization request is invalid")
        item.status = "denied"
        await write_audit_log(
            self.db,
            request,
            "OAUTH_CONSENT_DENIED",
            actor_id=user.id,
            subject_id=user.id,
            client_id=item.client_id,
        )
        params = {"error": "access_denied"}
        if item.state:
            params["state"] = item.state
        return callback_url(item.redirect_uri, params)

    async def authenticate_client(
        self,
        request: Request,
        client_id: str | None,
        client_secret: str | None,
    ) -> OAuthClient:
        basic_client_id, basic_secret = self._basic_credentials(request)
        resolved_id = basic_client_id or client_id
        resolved_secret = basic_secret or client_secret
        if not resolved_id:
            raise AppError(401, "invalid_client", "Client authentication is required")
        client = await self.repo.get_client(resolved_id)
        if not client or not client.enabled:
            raise AppError(401, "invalid_client", "Client authentication failed")
        if client.is_public or client.token_endpoint_auth_method == "none":
            return client
        if not resolved_secret or not client.client_secret_hash:
            raise AppError(401, "invalid_client", "Client authentication failed")
        if not verify_password(resolved_secret, client.client_secret_hash):
            raise AppError(401, "invalid_client", "Client authentication failed")
        return client

    @staticmethod
    def _basic_credentials(request: Request) -> tuple[str | None, str | None]:
        authorization = request.headers.get("authorization", "")
        if not authorization.lower().startswith("basic "):
            return None, None
        try:
            decoded = base64.b64decode(authorization[6:], validate=True).decode("utf-8")
            client_id, separator, secret = decoded.partition(":")
        except (ValueError, UnicodeDecodeError) as exc:
            raise AppError(401, "invalid_client", "Client authentication failed") from exc
        if not separator:
            raise AppError(401, "invalid_client", "Client authentication failed")
        return client_id, secret

    async def exchange_code(
        self,
        request: Request,
        client: OAuthClient,
        raw_code: str,
        redirect_uri: str,
        code_verifier: str,
    ) -> dict[str, object]:
        code_id, secret = split_opaque_token(raw_code, "cod")
        code = await self.repo.get_code(code_id)
        now = utcnow()
        if (
            not code
            or code.consumed_at is not None
            or as_utc(code.expires_at) <= now
            or code.client_id != client.client_id
            or code.redirect_uri != redirect_uri
            or not constant_time_secret_matches(secret, code.secret_hash)
            or not verify_pkce_s256(code_verifier, code.code_challenge)
        ):
            raise AppError(400, "invalid_grant", "Authorization code is invalid")
        code.consumed_at = now
        user = await UserRepository(self.db).get_by_id(code.user_id)
        session = await SessionRepository(self.db).get(code.session_id)
        if not user or user.status != "active" or not session or session.revoked_at:
            raise AppError(400, "invalid_grant", "Authorization grant is no longer active")
        response = await self._issue_token_set(
            request,
            user,
            client,
            session,
            code.scope,
            code.auth_time,
            code.nonce,
            family_id=None,
        )
        await write_audit_log(
            self.db,
            request,
            "OAUTH_TOKEN_ISSUED",
            actor_id=user.id,
            subject_id=user.id,
            client_id=client.client_id,
            details={"grant_type": "authorization_code", "scope": code.scope},
        )
        return response

    async def _issue_token_set(
        self,
        request: Request,
        user: User,
        client: OAuthClient,
        session: Session,
        scope: str,
        auth_time: datetime,
        nonce: str | None,
        family_id: str | None,
    ) -> dict[str, object]:
        scopes = set(parse_scope(scope))
        now = utcnow()
        amr = ["pwd", "otp"] if session.mfa_completed_at else ["pwd"]
        profile_claims: dict[str, object] = {
            "scope": scope,
            "client_id": client.client_id,
            "auth_time": int(as_utc(auth_time).timestamp()),
            "amr": amr,
            "acr": "urn:ods:loa:2" if session.mfa_completed_at else "urn:ods:loa:1",
        }
        if "email" in scopes:
            profile_claims.update({"email": user.email, "email_verified": user.email_verified})
        if "profile" in scopes and user.name:
            profile_claims["name"] = user.name
        jwt_service = get_jwt_service()
        access_token, access_claims = jwt_service.issue(
            user.id,
            client.client_id,
            "access",
            settings.access_token_ttl,
            profile_claims,
        )
        id_claims = {
            key: value
            for key, value in profile_claims.items()
            if key in {"auth_time", "amr", "acr", "email", "email_verified", "name"}
        }
        if nonce:
            id_claims["nonce"] = nonce
        id_token, _ = jwt_service.issue(
            user.id,
            client.client_id,
            "id",
            settings.id_token_ttl,
            id_claims,
        )
        self.db.add(
            OAuthAccessToken(
                jti=access_claims["jti"],
                user_id=user.id,
                client_id=client.client_id,
                session_id=session.id,
                scope=scope,
                issued_at=now,
                expires_at=now + timedelta(seconds=settings.access_token_ttl),
            )
        )
        refresh_id, refresh_secret, raw_refresh = issue_opaque_token("rft")
        family = family_id or f"fam_{secrets.token_urlsafe(18)}"
        await self.repo.add_refresh_token(
            RefreshToken(
                id=refresh_id,
                secret_hash=hash_secret(refresh_secret),
                family_id=family,
                user_id=user.id,
                client_id=client.client_id,
                session_id=session.id,
                scope=scope,
                expires_at=now + timedelta(seconds=settings.refresh_token_ttl),
            )
        )
        return {
            "access_token": access_token,
            "id_token": id_token,
            "token_type": "Bearer",
            "expires_in": settings.access_token_ttl,
            "refresh_token": raw_refresh,
            "scope": scope,
        }

    async def rotate_refresh_token(
        self, request: Request, client: OAuthClient, raw_token: str
    ) -> dict[str, object]:
        token_id, secret = split_opaque_token(raw_token, "rft")
        token = await self.repo.get_refresh_token(token_id)
        now = utcnow()
        if not token or token.client_id != client.client_id:
            raise AppError(400, "invalid_grant", "Refresh token is invalid")
        secret_valid = constant_time_secret_matches(secret, token.secret_hash)
        if not secret_valid:
            raise AppError(400, "invalid_grant", "Refresh token is invalid")
        if token.used_at is not None or token.revoked_at is not None:
            await self.repo.revoke_family(token.family_id, now)
            await self.repo.revoke_for_user_client(token.user_id, token.client_id, now)
            await SessionRepository(self.db).revoke_all(token.user_id, now)
            await write_audit_log(
                self.db,
                request,
                "REFRESH_TOKEN_REUSE_DETECTED",
                actor_id=token.user_id,
                subject_id=token.user_id,
                client_id=token.client_id,
                details={"family_id": token.family_id},
            )
            await self.db.commit()
            raise AppError(400, "invalid_grant", "Refresh token reuse was detected")
        if as_utc(token.expires_at) <= now:
            token.revoked_at = now
            raise AppError(400, "invalid_grant", "Refresh token is expired")
        user = await UserRepository(self.db).get_by_id(token.user_id)
        session = await SessionRepository(self.db).get(token.session_id)
        if (
            not user
            or user.status != "active"
            or not session
            or session.revoked_at is not None
            or as_utc(session.expires_at) <= now
        ):
            raise AppError(400, "invalid_grant", "Refresh grant is no longer active")
        token.used_at = now
        response = await self._issue_token_set(
            request,
            user,
            client,
            session,
            token.scope,
            session.created_at,
            None,
            token.family_id,
        )
        replacement_id, _ = split_opaque_token(str(response["refresh_token"]), "rft")
        token.replaced_by_id = replacement_id
        await write_audit_log(
            self.db,
            request,
            "REFRESH_TOKEN_ROTATED",
            actor_id=user.id,
            subject_id=user.id,
            client_id=client.client_id,
            details={"family_id": token.family_id},
        )
        return response

    async def revoke_token(self, request: Request, client: OAuthClient, raw_token: str) -> None:
        now = utcnow()
        if raw_token.startswith("rft_"):
            try:
                token_id, secret = split_opaque_token(raw_token, "rft")
            except AppError:
                return
            token = await self.repo.get_refresh_token(token_id)
            if (
                token
                and token.client_id == client.client_id
                and constant_time_secret_matches(secret, token.secret_hash)
            ):
                await self.repo.revoke_family(token.family_id, now)
                await write_audit_log(
                    self.db,
                    request,
                    "OAUTH_TOKEN_REVOKED",
                    actor_id=token.user_id,
                    subject_id=token.user_id,
                    client_id=client.client_id,
                    details={"token_type": "refresh"},
                )
            return
        try:
            claims = get_jwt_service().decode(raw_token, token_use="access")
        except AppError:
            return
        access = await self.repo.get_access_token(claims["jti"])
        if access and access.client_id == client.client_id:
            access.revoked_at = now
            await write_audit_log(
                self.db,
                request,
                "OAUTH_TOKEN_REVOKED",
                actor_id=access.user_id,
                subject_id=access.user_id,
                client_id=client.client_id,
                details={"token_type": "access"},
            )

    async def introspect(self, client: OAuthClient, raw_token: str) -> dict[str, object]:
        now = utcnow()
        if raw_token.startswith("rft_"):
            try:
                token_id, secret = split_opaque_token(raw_token, "rft")
            except AppError:
                return {"active": False}
            token = await self.repo.get_refresh_token(token_id)
            if (
                not token
                or token.client_id != client.client_id
                or token.revoked_at is not None
                or token.used_at is not None
                or as_utc(token.expires_at) <= now
                or not constant_time_secret_matches(secret, token.secret_hash)
            ):
                return {"active": False}
            return {
                "active": True,
                "sub": token.user_id,
                "client_id": token.client_id,
                "scope": token.scope,
                "exp": int(as_utc(token.expires_at).timestamp()),
                "token_type": "refresh_token",
            }
        try:
            claims = get_jwt_service().decode(raw_token, token_use="access")
        except AppError:
            return {"active": False}
        access = await self.repo.get_access_token(claims["jti"])
        if (
            not access
            or access.client_id != client.client_id
            or access.revoked_at is not None
            or as_utc(access.expires_at) <= now
        ):
            return {"active": False}
        return {
            "active": True,
            "sub": access.user_id,
            "client_id": access.client_id,
            "scope": access.scope,
            "exp": int(as_utc(access.expires_at).timestamp()),
            "iat": int(as_utc(access.issued_at).timestamp()),
            "token_type": "access_token",
        }

    async def userinfo(self, raw_token: str) -> dict[str, object]:
        claims = get_jwt_service().decode(raw_token, token_use="access")
        access = await self.repo.get_access_token(claims["jti"])
        now = utcnow()
        if not access or access.revoked_at is not None or as_utc(access.expires_at) <= now:
            raise AppError(401, "invalid_token", "Access token is inactive")
        user = await UserRepository(self.db).get_by_id(access.user_id)
        consent = await ConsentService(self.db).repo.get(access.user_id, access.client_id)
        if not user or not consent or consent.status != "granted":
            raise AppError(401, "invalid_token", "Access token is inactive")
        allowed = set(access.scope.split()) & set(consent.scopes)
        result: dict[str, object] = {"sub": user.id}
        if "email" in allowed:
            result.update({"email": user.email, "email_verified": user.email_verified})
        if "profile" in allowed and user.name:
            result["name"] = user.name
        return result


def create_client_secret() -> tuple[str, str]:
    raw = secrets.token_urlsafe(36)
    return raw, hash_password(raw)
