package uz.ods.sso.mfa

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.ods.sso.audit.AuditService
import uz.ods.sso.identity.BackupCodesResponse
import uz.ods.sso.identity.IdentityService
import uz.ods.sso.identity.LoginResponse
import uz.ods.sso.identity.MfaChallengeRequest
import uz.ods.sso.identity.StepUpRequest
import uz.ods.sso.identity.TotpEnableRequest
import uz.ods.sso.identity.TotpSetupResponse
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.session.SessionService
import java.time.Duration

@RestController
@RequestMapping("/api/v1/auth/mfa")
class MfaController(
    private val mfa: MfaService,
    private val identity: IdentityService,
    private val sessions: SessionService,
    private val crypto: CryptoService,
    private val audit: AuditService,
    private val limiter: RateLimiter,
) {
    @PostMapping("/verify")
    fun verify(
        @Valid @RequestBody body: MfaChallengeRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): LoginResponse {
        val rateLimitIdentity = mfa.loginChallengeRateLimitIdentity(body.challengeToken)
        limiter.enforce(RateLimiter.MFA, rateLimitIdentity) { retryAfter ->
            mfa.lockLoginChallenge(body.challengeToken, MFA_LOCK_DURATION)?.let { user ->
                audit.write(
                    user.tenantId,
                    request,
                    "MFA_RATE_LIMIT_LOCKED",
                    user.id,
                    user.id,
                    details = mapOf(
                        "locked_until" to user.lockedUntil.toString(),
                        "retry_after_seconds" to retryAfter,
                    ),
                )
            }
        }
        val result = mfa.verifyLoginChallenge(body.challengeToken, body.code, body.method)
        audit.write(
            result.user.tenantId,
            request,
            "MFA_VERIFIED",
            result.user.id,
            result.user.id,
            details = mapOf("method" to body.method),
        )
        return identity.completeLogin(
            result.user,
            request,
            response,
            true,
            result.riskScore,
            result.fingerprint,
        )
    }

    @PostMapping("/totp/setup")
    fun setup(request: HttpServletRequest): TotpSetupResponse {
        val principal = sessions.current()
        val (secret, uri) = mfa.setup(principal.user)
        audit.write(principal.user.tenantId, request, "MFA_SETUP_STARTED", principal.user.id, principal.user.id)
        return TotpSetupResponse(secret, uri, 600)
    }

    @PostMapping("/totp/enable")
    fun enable(
        @Valid @RequestBody body: TotpEnableRequest,
        request: HttpServletRequest,
    ): BackupCodesResponse {
        val principal = sessions.current()
        limiter.enforce(RateLimiter.MFA, principal.user.id)
        val codes = mfa.enable(principal.user, body.code)
        sessions.markMfaCompleted(principal.session.id)
        audit.write(principal.user.tenantId, request, "MFA_ENABLED", principal.user.id, principal.user.id)
        return BackupCodesResponse(codes)
    }

    @PostMapping("/backup/regenerate")
    fun regenerate(
        @Valid @RequestBody body: StepUpRequest,
        request: HttpServletRequest,
    ): BackupCodesResponse {
        val principal = sessions.current()
        if (!crypto.matchesPassword(body.password, principal.user.passwordHash)) {
            throw uz.ods.sso.shared.AppException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "invalid_credentials",
                "Step-up authentication failed",
            )
        }
        mfa.verifyStepUp(principal.user, body.code)
        val codes = mfa.regenerateBackupCodes(principal.user.id)
        audit.write(principal.user.tenantId, request, "BACKUP_CODES_REGENERATED", principal.user.id, principal.user.id)
        return BackupCodesResponse(codes)
    }

    companion object {
        val MFA_LOCK_DURATION: Duration = Duration.ofMinutes(30)
    }
}
