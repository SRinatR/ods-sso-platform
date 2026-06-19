import base64

import pytest
from cryptography.exceptions import InvalidTag

from app.errors import AppError
from app.security import (
    constant_time_secret_matches,
    decrypt_sensitive,
    encrypt_sensitive,
    get_jwt_service,
    hash_password,
    hash_secret,
    issue_opaque_token,
    sanitize_for_log,
    split_opaque_token,
    verify_password,
)
from app.services.oauth import parse_scope, verify_pkce_s256


def test_password_hashing_uses_argon2id() -> None:
    encoded = hash_password("CorrectHorseBattery2026!")
    assert encoded.startswith("$argon2id$")
    assert verify_password("CorrectHorseBattery2026!", encoded)
    assert not verify_password("wrong", encoded)
    assert not verify_password("x" * 129, encoded)
    with pytest.raises(AppError):
        hash_password("x" * 129)


def test_hmac_opaque_token_round_trip() -> None:
    token_id, secret, raw = issue_opaque_token("evt")
    parsed_id, parsed_secret = split_opaque_token(raw, "evt")
    assert parsed_id == token_id
    assert parsed_secret == secret
    assert constant_time_secret_matches(secret, hash_secret(secret))
    assert not constant_time_secret_matches("other", hash_secret(secret))
    with pytest.raises(AppError):
        split_opaque_token("malformed", "evt")


def test_aes_gcm_detects_tampering() -> None:
    encrypted = encrypt_sensitive("JBSWY3DPEHPK3PXP", "totp:user")
    assert decrypt_sensitive(encrypted, "totp:user") == "JBSWY3DPEHPK3PXP"
    payload = bytearray(base64.urlsafe_b64decode(encrypted))
    payload[-1] ^= 1
    with pytest.raises(InvalidTag):
        decrypt_sensitive(base64.urlsafe_b64encode(payload).decode(), "totp:user")


def test_jwt_rs256_and_jwks() -> None:
    service = get_jwt_service()
    token, claims = service.issue("usr_test", "client_test", "access", 60, {"scope": "openid"})
    decoded = service.decode(token, audience="client_test", token_use="access")
    assert decoded["sub"] == "usr_test"
    assert decoded["jti"] == claims["jti"]
    key = service.jwks()["keys"][0]
    assert key["alg"] == "RS256"
    assert key["kid"]
    with pytest.raises(AppError):
        service.decode(token, audience="wrong-client")


def test_pkce_scope_and_log_redaction() -> None:
    verifier = "a" * 64
    import hashlib

    challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    )
    assert verify_pkce_s256(verifier, challenge)
    assert not verify_pkce_s256("b" * 64, challenge)
    assert parse_scope("openid email email") == ["openid", "email"]
    with pytest.raises(AppError):
        parse_scope("email")
    with pytest.raises(AppError):
        parse_scope("openid admin")
    redacted = sanitize_for_log(
        {"email": "u@example.com", "password": "secret", "nested": {"access_token": "raw"}}
    )
    assert redacted["password"] == "[redacted]"
    assert redacted["nested"]["access_token"] == "[redacted]"
