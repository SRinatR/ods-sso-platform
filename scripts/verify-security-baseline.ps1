$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$violations = [System.Collections.Generic.List[string]]::new()

function Assert-Pattern {
    param(
        [string]$Rule,
        [string]$RelativePath,
        [string]$Pattern,
        [string]$Expectation
    )

    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        $violations.Add("${Rule}: missing file ${RelativePath}")
        return
    }

    $content = Get-Content -LiteralPath $path -Raw
    if ($content -notmatch $Pattern) {
        $violations.Add("${Rule}: ${Expectation} (${RelativePath})")
    }
}

function Assert-Absent {
    param(
        [string]$Rule,
        [string]$RelativePath,
        [string]$Pattern,
        [string]$Expectation
    )

    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        $violations.Add("${Rule}: missing file ${RelativePath}")
        return
    }

    $content = Get-Content -LiteralPath $path -Raw
    if ($content -match $Pattern) {
        $violations.Add("${Rule}: ${Expectation} (${RelativePath})")
    }
}

$securityConfig = "backend-kotlin/src/main/kotlin/uz/ods/sso/config/SecurityConfiguration.kt"
$oauthProvisioning = "backend-kotlin/src/main/kotlin/uz/ods/sso/oauth/OAuthClientProvisioningService.kt"
$rotationService = "backend-kotlin/src/main/kotlin/uz/ods/sso/oauth/RotationTrackingAuthorizationService.kt"
$sessionService = "backend-kotlin/src/main/kotlin/uz/ods/sso/session/SessionService.kt"
$cryptoService = "backend-kotlin/src/main/kotlin/uz/ods/sso/security/CryptoService.kt"
$identityService = "backend-kotlin/src/main/kotlin/uz/ods/sso/identity/IdentityService.kt"
$rateLimiter = "backend-kotlin/src/main/kotlin/uz/ods/sso/security/RateLimiter.kt"
$auditService = "backend-kotlin/src/main/kotlin/uz/ods/sso/audit/AuditService.kt"
$mfaService = "backend-kotlin/src/main/kotlin/uz/ods/sso/mfa/MfaService.kt"
$mfaController = "backend-kotlin/src/main/kotlin/uz/ods/sso/mfa/MfaController.kt"
$loggingSanitizer = "backend-kotlin/src/main/kotlin/uz/ods/sso/shared/logging/LoggingSanitizer.kt"
$ephemeralStore = "backend-kotlin/src/main/kotlin/uz/ods/sso/security/EphemeralStore.kt"
$domainEvents = "backend-kotlin/src/main/kotlin/uz/ods/sso/events/DomainEvents.kt"

Assert-Pattern "SEC-BASE-001" $oauthProvisioning '\.requireProofKey\(true\)' "PKCE must remain mandatory"
Assert-Pattern "SEC-BASE-001" $securityConfig 'code_challenge_method.*S256|Only PKCE S256 is supported' "PKCE method must remain S256-only"
Assert-Pattern "SEC-BASE-002" $oauthProvisioning 'AuthorizationGrantType\.AUTHORIZATION_CODE' "authorization_code grant must be configured"
Assert-Absent "SEC-BASE-002" $oauthProvisioning 'AuthorizationGrantType\.(PASSWORD|IMPLICIT)' "deprecated password or implicit grant is prohibited"
Assert-Pattern "SEC-BASE-003" $oauthProvisioning 'uri\.fragment != null' "redirect URIs with fragments must be rejected"
Assert-Pattern "SEC-BASE-003" $oauthProvisioning 'uri\?\.scheme == "https"' "production redirect URIs must use HTTPS"
Assert-Pattern "SEC-BASE-004" $sessionService 'crypto\.opaqueToken\("ses"\)' "first-party sessions must use opaque tokens"
Assert-Pattern "SEC-BASE-004" $sessionService 'isHttpOnly = true' "session cookies must remain HttpOnly"
Assert-Pattern "SEC-BASE-004" $sessionService 'setAttribute\("SameSite", "Lax"\)' "session cookies must remain SameSite=Lax"
Assert-Pattern "SEC-BASE-004" $securityConfig '(?s)authorizationServerSecurityFilterChain.*?RequestAttributeSecurityContextRepository\(\).*?return http\.build\(\)' "authorization server must not restore first-party identities from HttpSession"
Assert-Pattern "SEC-BASE-004" $securityConfig '(?s)authorizationServerSecurityFilterChain.*?SessionCreationPolicy\.STATELESS.*?return http\.build\(\)' "authorization server must remain stateless and use ods_session per request"
Assert-Pattern "SEC-BASE-004" $securityConfig '(?s)authorizationServerSecurityFilterChain.*?requestCache \{ it\.disable\(\) \}.*?return http\.build\(\)' "authorization server must not use Spring request-cache fallback state"
Assert-Pattern "SEC-BASE-005" $oauthProvisioning 'idTokenSignatureAlgorithm\(SignatureAlgorithm\.RS256\)' "ID tokens must use RS256"
Assert-Pattern "SEC-BASE-005" $securityConfig 'initialize\(3072\)' "development RSA keys must remain at least 2048 bits"
Assert-Pattern "SEC-BASE-006" $oauthProvisioning 'reuseRefreshTokens\(false\)' "refresh-token rotation must remain enabled"
Assert-Pattern "SEC-BASE-006" $rotationService 'REFRESH_TOKEN_REUSE_DETECTED' "refresh-token reuse detection must remain audited"
Assert-Pattern "SEC-BASE-006" $rotationService 'sessions\.revokeAll' "refresh-token reuse must revoke active sessions"
Assert-Pattern "SEC-BASE-007" $cryptoService '(?s)Argon2PasswordEncoder\(\s*16,\s*32,\s*4,\s*131_072,\s*4,\s*\)' "Argon2id parameters must remain m=131072,t=4,p=4"
Assert-Absent "SEC-BASE-007" $cryptoService 'BCryptPasswordEncoder|Pbkdf2PasswordEncoder|SCryptPasswordEncoder' "prohibited password encoders must not be introduced"
Assert-Pattern "SEC-BASE-008" "Caddyfile.production" 'protocols\s+tls1\.2\s+tls1\.3' "production TLS must explicitly support TLS 1.2 and TLS 1.3"
Assert-Pattern "SEC-BASE-008" "Caddyfile.staging" 'protocols\s+tls1\.3' "staging TLS minimum must remain TLS 1.3"
Assert-Pattern "SEC-BASE-008" "docker-compose.yml" 'image:\s+caddy:2\.11\.1-alpine' "Caddy must remain pinned to the reviewed runtime version"
Assert-Pattern "SEC-BASE-009" $cryptoService 'Mac\.getInstance\("HmacSHA256"\)' "opaque token MACs must use HMAC-SHA256"
Assert-Pattern "SEC-BASE-009" $cryptoService 'MessageDigest\.isEqual' "secret verification must use constant-time comparison"
Assert-Pattern "SEC-BASE-010" $cryptoService 'dummyPasswordHash' "unknown-user login must execute password verification"
Assert-Pattern "SEC-BASE-010" $identityService 'withAuthenticationTiming' "authentication timing floor must remain enabled"
Assert-Pattern "SEC-BASE-010" $identityService '(?s)val existing = users\.findByTenantIdAndEmailIgnoreCase\(tenant\.id, email\).*if \(existing != null\) \{.*return properties\.requireEmailVerification' "duplicate registration must retain the generic registration response"
Assert-Pattern "SEC-BASE-010" $identityService 'properties\.requireEmailVerification && !existing\.emailVerified' "unverified duplicate registration may resend without disclosing account state"
Assert-Pattern "SEC-BASE-011" $auditService '"access_token"' "audit redaction must cover access tokens"
Assert-Pattern "SEC-BASE-011" $auditService '"client_secret"' "audit redaction must cover client secrets"
Assert-Pattern "SEC-BASE-011" $loggingSanitizer 'class LoggingSanitizerInstaller' "process-wide logging sanitizer must remain installed"
Assert-Pattern "SEC-BASE-011" $loggingSanitizer 'sanitizeThrowable' "throwable messages must be sanitized"
Assert-Absent "SEC-BASE-011" $ephemeralStore 'redis_ephemeral_fallback key=' "ephemeral token-bearing keys must not be logged"
Assert-Pattern "SEC-BASE-011" $domainEvents 'LoggingSanitizer::sanitize' "persisted outbox error messages must be sanitized"
Assert-Pattern "SEC-BASE-012" $rateLimiter 'RateLimitRule\("login", 5, Duration\.ofMinutes\(15\)\)' "login limit must remain 5 attempts per 15 minutes"
Assert-Pattern "SEC-BASE-012" $rateLimiter 'RateLimitRule\("registration_burst", 10, Duration\.ofMinutes\(10\)\)' "registration burst limit must remain 10 attempts per 10 minutes"
Assert-Pattern "SEC-BASE-012" $rateLimiter 'RateLimitRule\("registration_daily", 50, Duration\.ofDays\(1\)\)' "registration daily limit must remain 50 attempts per day"
Assert-Pattern "SEC-BASE-012" $rateLimiter 'RateLimitRule\("email_action", 5, Duration\.ofMinutes\(15\)\)' "verification and reset email limit must remain 5 attempts per 15 minutes"
Assert-Pattern "SEC-BASE-012" $rateLimiter 'RateLimitRule\("mfa", 3, Duration\.ofMinutes\(1\)\)' "MFA limit must remain 3 attempts per minute"
Assert-Pattern "SEC-BASE-012" $rateLimiter 'ZREMRANGEBYSCORE' "rate limits must use a Redis sliding window"
Assert-Pattern "SEC-BASE-012" $rateLimiter 'ZADD' "rate-limit attempts must be recorded atomically"
Assert-Pattern "SEC-BASE-012" $rateLimiter "redis\.call\('TIME'\)" "distributed rate limits must use the Redis server clock"
Assert-Pattern "SEC-BASE-012" $rateLimiter '"Retry-After"' "rate-limit responses must include Retry-After"
Assert-Pattern "SEC-BASE-012" $mfaController 'Duration\.ofMinutes\(30\)' "MFA overflow must impose a 30-minute account lock"
Assert-Pattern "SEC-BASE-012" $mfaController 'loginChallengeRateLimitIdentity' "MFA limits must be isolated per challenged account"
Assert-Pattern "SEC-BASE-012" $mfaController '"setup:\$\{principal\.user\.id\}"' "MFA enrollment limits must not consume login challenge attempts"
Assert-Pattern "SEC-BASE-012" $mfaService 'user\.lockedUntil = now\.plus\(duration\)' "MFA account lock must be persisted"

if ($violations.Count -gt 0) {
    Write-Error ("SEC-BASE rule violation detected:`n- " + ($violations -join "`n- "))
}

Write-Output "SEC-BASE immutable checks passed (12 rule groups)."
