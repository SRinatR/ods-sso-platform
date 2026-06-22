package uz.ods.sso.identity

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.events.DomainEventService
import uz.ods.sso.persistence.AccountTokenEntity
import uz.ods.sso.persistence.AccountTokenRepository
import uz.ods.sso.persistence.LoginHistoryEntity
import uz.ods.sso.persistence.LoginHistoryRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.risk.RiskService
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.EphemeralStore
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import uz.ods.sso.shared.clientIp
import uz.ods.sso.tenant.TenantService
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ThreadLocalRandom

@Service
class IdentityService(
    private val tenants: TenantService,
    private val users: UserRepository,
    private val tokens: AccountTokenRepository,
    private val history: LoginHistoryRepository,
    private val crypto: CryptoService,
    private val mail: MailService,
    private val audit: AuditService,
    private val events: DomainEventService,
    private val rateLimiter: RateLimiter,
    private val risk: RiskService,
    private val ephemeral: EphemeralStore,
    private val sessions: SessionService,
    private val properties: OdsProperties,
) {
    @Transactional
    fun register(body: RegisterRequest, request: HttpServletRequest): Boolean {
        rateLimiter.enforce(RateLimiter.REGISTRATION, clientIp(request, properties))
        if (!body.acceptTerms) {
            throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "terms_required", "Terms must be accepted")
        }
        if (properties.requireEmailVerification) requireMailDelivery()
        val tenant = tenants.current()
        val email = body.email.trim().lowercase()
        val passwordHash = crypto.hashPassword(body.password)
        if (users.findByTenantIdAndEmailIgnoreCase(tenant.id, email) != null) {
            return properties.requireEmailVerification
        }
        val user = users.save(
            UserEntity(
                tenantId = tenant.id,
                email = email,
                passwordHash = passwordHash,
                name = body.name.trim(),
                emailVerifiedAt = Instant.now().takeUnless { properties.requireEmailVerification },
            ),
        )
        audit.write(tenant.id, request, "USER_REGISTERED", user.id, user.id)
        events.append(tenant.id, "UserRegistered", user.id, mapOf("user_id" to user.id, "email" to user.email))
        if (!properties.requireEmailVerification) {
            audit.write(tenant.id, request, "EMAIL_VERIFICATION_SKIPPED", user.id, user.id)
            return false
        }
        val raw = createAccountToken(user.id, "email_verification", properties.verificationTokenTtl, "evt")
        mail.sendVerification(email, raw)
        audit.write(tenant.id, request, "EMAIL_VERIFICATION_SENT", user.id, user.id)
        return true
    }

    @Transactional
    fun verifyEmail(rawToken: String, request: HttpServletRequest) {
        val token = validateAccountToken(rawToken, "evt", "email_verification")
        val user = users.findByPublicId(token.userId) ?: throw
            AppException(HttpStatus.BAD_REQUEST, "invalid_verification_token", "Verification token is invalid")
        val now = Instant.now()
        token.usedAt = now
        user.emailVerifiedAt = now
        audit.write(user.tenantId, request, "EMAIL_VERIFIED", user.id, user.id)
        events.append(user.tenantId, "EmailVerified", user.id, mapOf("user_id" to user.id))
    }

    @Transactional
    fun resendVerification(emailInput: String, request: HttpServletRequest) {
        requireMailDelivery()
        rateLimiter.enforce(RateLimiter.REGISTRATION, "resend:${clientIp(request, properties)}")
        val tenant = tenants.current()
        val user = users.findByTenantIdAndEmailIgnoreCase(tenant.id, emailInput.lowercase())
        if (user != null && !user.emailVerified) {
            val raw = createAccountToken(user.id, "email_verification", properties.verificationTokenTtl, "evt")
            mail.sendVerification(user.email, raw)
            audit.write(tenant.id, request, "EMAIL_VERIFICATION_SENT", user.id, user.id)
        }
    }

    @Transactional
    fun forgotPassword(emailInput: String, request: HttpServletRequest) {
        requireMailDelivery()
        rateLimiter.enforce(RateLimiter.REGISTRATION, "reset:${clientIp(request, properties)}")
        val tenant = tenants.current()
        val user = users.findByTenantIdAndEmailIgnoreCase(tenant.id, emailInput.lowercase()) ?: return
        val raw = createAccountToken(user.id, "password_reset", properties.passwordResetTokenTtl, "prt")
        mail.sendPasswordReset(user.email, raw)
        audit.write(tenant.id, request, "PASSWORD_RESET_REQUESTED", user.id, user.id)
    }

    @Transactional
    fun resetPassword(body: ResetPasswordRequest, request: HttpServletRequest) {
        val token = validateAccountToken(body.token, "prt", "password_reset")
        val user = users.findByPublicId(token.userId) ?: throw
            AppException(HttpStatus.BAD_REQUEST, "invalid_reset_token", "Password reset token is invalid")
        val now = Instant.now()
        token.usedAt = now
        tokens.invalidate(user.id, "password_reset", now)
        user.passwordHash = crypto.hashPassword(body.newPassword)
        sessions.revokeAll(user.id)
        audit.write(user.tenantId, request, "PASSWORD_RESET_COMPLETED", user.id, user.id)
        events.append(user.tenantId, "PasswordChanged", user.id, mapOf("user_id" to user.id))
        mail.trySendPasswordChanged(user.email)
    }

    @Transactional(noRollbackFor = [AppException::class])
    fun login(
        body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): LoginResponse = withAuthenticationTiming {
        rateLimiter.enforce(RateLimiter.LOGIN, clientIp(request, properties))
        val tenant = tenants.current()
        val email = body.email.lowercase()
        val user = users.findByTenantIdAndEmailIgnoreCase(tenant.id, email)
        val now = Instant.now()
        if (user?.lockedUntil?.isAfter(now) == true) {
            recordLogin(tenant.id, user, email, false, "account_locked", 0, request)
            audit.write(tenant.id, request, "LOGIN_FAILED", user.id, user.id, details = mapOf("reason" to "account_locked"))
            throw AppException(HttpStatus.LOCKED, "account_locked", "Account is temporarily locked")
        }
        if (!crypto.matchesPassword(body.password, user?.passwordHash)) {
            if (user != null) {
                user.failedLoginCount += 1
                if (user.failedLoginCount >= 5) user.lockedUntil = now.plus(15, ChronoUnit.MINUTES)
            }
            recordLogin(tenant.id, user, email, false, "invalid_credentials", 0, request)
            audit.write(tenant.id, request, "LOGIN_FAILED", user?.id, user?.id, details = mapOf("reason" to "invalid_credentials"))
            throw AppException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect")
        }
        val authenticatedUser = requireNotNull(user)
        if (authenticatedUser.status != "active") {
            throw AppException(HttpStatus.FORBIDDEN, "account_unavailable", "Account is not active")
        }
        if (!authenticatedUser.emailVerified) {
            throw AppException(HttpStatus.FORBIDDEN, "email_not_verified", "Email verification is required")
        }

        authenticatedUser.failedLoginCount = 0
        authenticatedUser.lockedUntil = null
        val riskResult = risk.assess(authenticatedUser, clientIp(request, properties), request.getHeader("User-Agent"))
        if (riskResult.decision == "deny") {
            recordLogin(tenant.id, authenticatedUser, email, false, "risk_denied", riskResult.score, request)
            audit.write(
                tenant.id,
                request,
                "RISK_LOGIN_DENIED",
                authenticatedUser.id,
                authenticatedUser.id,
                details = mapOf("score" to riskResult.score),
            )
            throw AppException(HttpStatus.FORBIDDEN, "risk_denied", "Login was blocked by risk policy")
        }
        if (authenticatedUser.mfaEnabled) {
            val challenge = crypto.randomUrl(32)
            ephemeral.set(
                "mfa:challenge:$challenge",
                listOf(authenticatedUser.id, riskResult.score, riskResult.fingerprint).joinToString("|"),
                Duration.ofSeconds(properties.preauthTokenTtl),
            )
            audit.write(
                tenant.id,
                request,
                "MFA_CHALLENGE_ISSUED",
                authenticatedUser.id,
                authenticatedUser.id,
            )
            return@withAuthenticationTiming LoginResponse(mfaRequired = true, challengeToken = challenge)
        }
        completeLogin(authenticatedUser, request, response, false, riskResult.score, riskResult.fingerprint)
    }

    @Transactional
    fun completeLogin(
        user: UserEntity,
        request: HttpServletRequest,
        response: HttpServletResponse,
        mfaCompleted: Boolean,
        riskScore: Int,
        fingerprint: String?,
    ): LoginResponse {
        sessions.create(request, response, user, mfaCompleted, riskScore, fingerprint)
        user.lastLoginAt = Instant.now()
        recordLogin(user.tenantId, user, user.email, true, null, riskScore, request)
        audit.write(user.tenantId, request, "LOGIN_SUCCESS", user.id, user.id, details = mapOf("mfa" to mfaCompleted, "risk_score" to riskScore))
        events.append(user.tenantId, "UserLoggedIn", user.id, mapOf("user_id" to user.id, "risk_score" to riskScore))
        return LoginResponse(userId = user.id, email = user.email)
    }

    fun challenge(challengeToken: String): Triple<UserEntity, Int, String?> {
        val raw = ephemeral.get("mfa:challenge:$challengeToken")
            ?: throw AppException(HttpStatus.UNAUTHORIZED, "invalid_mfa_challenge", "MFA challenge is invalid")
        val parts = raw.split("|")
        val user = users.findByPublicId(parts[0]) ?: throw
            AppException(HttpStatus.UNAUTHORIZED, "invalid_mfa_challenge", "MFA challenge is invalid")
        return Triple(user, parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2))
    }

    fun consumeChallenge(challengeToken: String) = ephemeral.delete("mfa:challenge:$challengeToken")

    private fun createAccountToken(userId: String, type: String, ttlSeconds: Long, prefix: String): String {
        val (id, secret, raw) = crypto.opaqueToken(prefix)
        tokens.save(
            AccountTokenEntity(
                userId = userId,
                type = type,
                secretHash = crypto.hashSecret(secret),
                expiresAt = Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS),
            ).apply { publicId = id },
        )
        return raw
    }

    private fun validateAccountToken(raw: String, prefix: String, type: String): AccountTokenEntity {
        val (id, secret) = crypto.splitToken(raw, prefix)
        val token = tokens.findByPublicIdAndType(id, type)
        if (
            token == null ||
            token.usedAt != null ||
            !token.expiresAt.isAfter(Instant.now()) ||
            !crypto.secretMatches(secret, token.secretHash)
        ) {
            val code = if (type == "password_reset") "invalid_reset_token" else "invalid_verification_token"
            val message = if (type == "password_reset") "Password reset token is invalid" else "Verification token is invalid"
            throw AppException(HttpStatus.BAD_REQUEST, code, message)
        }
        return token
    }

    private fun recordLogin(
        tenantId: String,
        user: UserEntity?,
        email: String,
        success: Boolean,
        reason: String?,
        riskScore: Int,
        request: HttpServletRequest,
    ) {
        history.save(
            LoginHistoryEntity(
                tenantId = tenantId,
                userId = user?.id,
                email = email,
                success = success,
                failureReason = reason,
                ipAddress = clientIp(request, properties),
                userAgent = request.getHeader("User-Agent"),
                riskScore = riskScore,
            ),
        )
    }

    private fun requireMailDelivery() {
        if (!mail.available) {
            throw AppException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "email_delivery_unavailable",
                "Email delivery is temporarily unavailable",
            )
        }
    }

    private fun <T> withAuthenticationTiming(block: () -> T): T {
        val started = System.nanoTime()
        val minimumDurationMillis = ThreadLocalRandom.current().nextLong(100, 201)
        try {
            return block()
        } finally {
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            val remainingMillis = minimumDurationMillis - elapsedMillis
            if (remainingMillis > 0) {
                try {
                    Thread.sleep(remainingMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}
