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
import uz.ods.sso.security.StepUpService
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

@RestController
@RequestMapping("/api/v1/auth")
class IdentityController(
    private val identity: IdentityService,
    private val sessions: SessionService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
) {
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody body: RegisterRequest,
        request: HttpServletRequest,
    ): ResponseEntity<RegistrationResponse> {
        identity.register(body, request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(
                RegistrationResponse(
                    message = "Verification code sent. Confirm your email to continue.",
                    email = body.email.trim().lowercase(),
                ),
            )
    }

    @PostMapping("/verify-email")
    fun verifyEmail(
        @Valid @RequestBody body: VerifyEmailRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): LoginResponse = identity.verifyEmail(body, request, response)

    @PostMapping("/resend-verification")
    fun resend(
        @Valid @RequestBody body: ResendVerificationRequest,
        request: HttpServletRequest,
    ): MessageResponse {
        identity.resendVerification(body.email, request)
        return MessageResponse(
            message = "If the account exists and is unverified, the email provider accepted a verification message.",
        )
    }

    @PostMapping("/forgot-password")
    fun forgot(
        @Valid @RequestBody body: ForgotPasswordRequest,
        request: HttpServletRequest,
    ): MessageResponse {
        identity.forgotPassword(body.email, request)
        return MessageResponse(
            message = "If the account exists, the email provider accepted a password reset message.",
        )
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
        val principal = sessions.current()
        return principal.user.toResponse(principal.session.authenticationMethod)
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
        try {
            stepUp.verifyAndMark(principal, body.password, body.code)
        } catch (exception: AppException) {
            audit.write(principal.user.tenantId, request, "STEP_UP_FAILED", principal.user.id, principal.user.id)
            throw exception
        }
        audit.write(principal.user.tenantId, request, "STEP_UP_COMPLETED", principal.user.id, principal.user.id)
        return MessageResponse(message = "Step-up authentication completed")
    }
}

fun uz.ods.sso.persistence.UserEntity.toResponse(authenticationMethod: String? = null) = UserResponse(
    id = id,
    email = email,
    name = name,
    firstNameCyrillic = firstNameCyrillic,
    lastNameCyrillic = lastNameCyrillic,
    patronymicCyrillic = patronymicCyrillic,
    firstNameLatin = firstNameLatin,
    lastNameLatin = lastNameLatin,
    patronymicLatin = patronymicLatin,
    fullNameCyrillic = fullNameCyrillic,
    fullNameLatin = fullNameLatin,
    profilePictureUrl = profilePictureUrl,
    phone = phone,
    emailVerified = emailVerified,
    status = status,
    role = role,
    mfaEnabled = mfaEnabled,
    authenticationMethod = authenticationMethod,
    createdAt = createdAt,
)
