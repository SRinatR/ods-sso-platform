package uz.ods.sso.identity

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.mfa.MfaService
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

@RestController
@RequestMapping("/api/v1/auth")
class IdentityController(
    private val identity: IdentityService,
    private val sessions: SessionService,
    private val mfa: MfaService,
    private val crypto: CryptoService,
    private val audit: AuditService,
    private val properties: OdsProperties,
) {
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody body: RegisterRequest,
        request: HttpServletRequest,
    ): ResponseEntity<RegistrationResponse> {
        val verificationRequired = identity.register(body, request)
        val message = if (verificationRequired) {
            "Registration completed. Verify your email to continue."
        } else {
            "Registration completed. You can sign in now."
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(RegistrationResponse(message = message, verificationRequired = verificationRequired))
    }

    @PostMapping("/verify-email")
    fun verifyEmail(@Valid @RequestBody body: VerifyEmailRequest, request: HttpServletRequest): MessageResponse {
        identity.verifyEmail(body.token, request)
        return MessageResponse(message = "Email verified")
    }

    @PostMapping("/resend-verification")
    fun resend(
        @Valid @RequestBody body: ResendVerificationRequest,
        request: HttpServletRequest,
    ): MessageResponse {
        identity.resendVerification(body.email, request)
        return MessageResponse(message = "If the account exists and is unverified, a verification email was sent.")
    }

    @PostMapping("/forgot-password")
    fun forgot(
        @Valid @RequestBody body: ForgotPasswordRequest,
        request: HttpServletRequest,
    ): MessageResponse {
        identity.forgotPassword(body.email, request)
        return MessageResponse(message = "If the account exists, a password reset email was sent.")
    }

    @PostMapping("/reset-password")
    fun reset(
        @Valid @RequestBody body: ResetPasswordRequest,
        request: HttpServletRequest,
    ): MessageResponse {
        identity.resetPassword(body, request)
        return MessageResponse(message = "Password reset completed")
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): LoginResponse = identity.login(body, request, response)

    @GetMapping("/me")
    fun me(): UserResponse {
        val user = sessions.current().user
        return user.toResponse()
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest, response: HttpServletResponse): MessageResponse {
        val principal = sessions.current()
        sessions.revoke(principal.user.id, principal.session.id)
        sessions.clearCookie(response)
        audit.write(principal.user.tenantId, request, "LOGOUT", principal.user.id, principal.user.id)
        return MessageResponse(message = "Logged out")
    }

    @PostMapping("/logout-all")
    fun logoutAll(request: HttpServletRequest, response: HttpServletResponse): MessageResponse {
        val principal = sessions.current()
        val count = sessions.revokeAll(principal.user.id)
        sessions.clearCookie(response)
        audit.write(
            principal.user.tenantId,
            request,
            "LOGOUT_ALL",
            principal.user.id,
            principal.user.id,
            details = mapOf("revoked_sessions" to count),
        )
        return MessageResponse(message = "All sessions were revoked")
    }

    @PostMapping("/step-up")
    fun stepUp(
        @Valid @RequestBody body: StepUpRequest,
        request: HttpServletRequest,
    ): MessageResponse {
        val principal = sessions.current()
        if (!crypto.matchesPassword(body.password, principal.user.passwordHash)) {
            audit.write(principal.user.tenantId, request, "STEP_UP_FAILED", principal.user.id, principal.user.id)
            throw AppException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Step-up authentication failed")
        }
        mfa.verifyStepUp(principal.user, body.code)
        sessions.markStepUp(principal.session.id)
        audit.write(principal.user.tenantId, request, "STEP_UP_COMPLETED", principal.user.id, principal.user.id)
        return MessageResponse(message = "Step-up authentication completed")
    }
}

fun uz.ods.sso.persistence.UserEntity.toResponse() = UserResponse(
    id = id,
    email = email,
    name = name,
    emailVerified = emailVerified,
    status = status,
    role = role,
    mfaEnabled = mfaEnabled,
    createdAt = createdAt,
)
