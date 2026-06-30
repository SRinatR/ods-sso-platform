package uz.ods.sso.identity

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.events.DomainEventService
import uz.ods.sso.persistence.AccountTokenEntity
import uz.ods.sso.persistence.AccountTokenRepository
import uz.ods.sso.persistence.LoginHistoryEntity
import uz.ods.sso.persistence.LoginHistoryRepository
import uz.ods.sso.persistence.TenantEntity
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.risk.RiskService
import uz.ods.sso.risk.RiskResult
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.EphemeralStore
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.session.SessionService
import uz.ods.sso.session.OdsPrincipal
import uz.ods.sso.shared.AppException
import uz.ods.sso.tenant.TenantService
import java.time.Instant

class IdentityServiceTest {
    private val properties = OdsProperties(
        environment = "test",
        requireEmailVerification = false,
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val tenants = mock<TenantService>()
    private val users = mock<UserRepository>()
    private val tokens = mock<AccountTokenRepository>()
    private val history = mock<LoginHistoryRepository>()
    private val crypto = CryptoService(properties)
    private val mail = mock<MailService>()
    private val audit = mock<AuditService>()
    private val events = mock<DomainEventService>()
    private val rateLimiter = mock<RateLimiter>()
    private val risk = mock<RiskService>()
    private val ephemeral = mock<EphemeralStore>()
    private val sessions = mock<SessionService>()
    private val service = IdentityService(
        tenants,
        users,
        tokens,
        history,
        crypto,
        mail,
        audit,
        events,
        rateLimiter,
        risk,
        ephemeral,
        sessions,
        properties,
    )
    private val tenant = TenantEntity(slug = "default", name = "ODS").apply { publicId = "tnt_1" }
    private val request = mock<HttpServletRequest>()

    @Test
    fun `registration creates minimal account and does not disclose duplicate email`() {
        whenever(tenants.current()).thenReturn(tenant)
        whenever(users.findByTenantIdAndEmailIgnoreCase("tnt_1", "user@example.com")).thenReturn(null)
        whenever(users.save(any<UserEntity>())).thenAnswer { it.arguments[0] }

        assertThat(
            service.register(RegisterRequest("user@example.com"), request),
        ).isFalse()
        verify(rateLimiter).enforce(RateLimiter.REGISTRATION_BURST, "unknown")
        verify(rateLimiter).enforce(RateLimiter.REGISTRATION_DAILY, "unknown")
        val created = org.mockito.kotlin.argumentCaptor<UserEntity>()
        verify(users).save(created.capture())
        assertThat(created.firstValue.name).isNull()
        assertThat(created.firstValue.fullNameCyrillic).isNull()
        assertThat(created.firstValue.fullNameLatin).isNull()
        assertThat(created.firstValue.emailVerified).isTrue()
        assertThat(created.firstValue.termsAcceptedAt).isNotNull()

        whenever(users.findByTenantIdAndEmailIgnoreCase("tnt_1", "user@example.com")).thenReturn(UserEntity())
        assertThat(
            service.register(RegisterRequest("user@example.com"), request),
        ).isFalse()
    }

    @Test
    fun `repeated registration sends a fresh verification code for an existing account`() {
        val verifiedProperties = properties.copy(requireEmailVerification = true)
        val verifiedService = IdentityService(
            tenants,
            users,
            tokens,
            history,
            crypto,
            mail,
            audit,
            events,
            rateLimiter,
            risk,
            ephemeral,
            sessions,
            verifiedProperties,
        )
        val user = UserEntity(
            tenantId = "tnt_1",
            email = "user@example.com",
            passwordHash = crypto.hashPassword("existing-password-value"),
        ).apply { publicId = "usr_1" }
        whenever(mail.available).thenReturn(true)
        whenever(tenants.current()).thenReturn(tenant)
        whenever(users.findByTenantIdAndEmailIgnoreCase("tnt_1", "user@example.com")).thenReturn(user)
        whenever(tokens.save(any<AccountTokenEntity>())).thenAnswer { it.arguments[0] }

        assertThat(
            verifiedService.register(RegisterRequest("user@example.com"), request),
        ).isTrue()

        verify(tokens).invalidate(eq("usr_1"), eq("email_verification"), any())
        verify(mail).sendVerification(eq("user@example.com"), any())
    }

    @Test
    fun `email verification and password reset consume valid opaque tokens`() {
        val response = mock<HttpServletResponse>()
        val user = UserEntity(
            tenantId = "tnt_1",
            email = "user@example.com",
            passwordHash = crypto.hashPassword("old-password-value"),
        ).apply { publicId = "usr_1" }
        whenever(users.findByPublicId("usr_1")).thenReturn(user)
        whenever(request.remoteAddr).thenReturn("127.0.0.1")
        whenever(request.getHeader("User-Agent")).thenReturn("test")
        whenever(risk.assess(user, "127.0.0.1", "test"))
            .thenReturn(RiskResult(0, "allow", emptyList(), "fingerprint"))

        val (verificationId, verificationSecret, verificationRaw) = crypto.opaqueToken("evt")
        val verification = AccountTokenEntity(
            userId = "usr_1",
            type = "email_verification",
            secretHash = crypto.hashSecret(verificationSecret),
            expiresAt = Instant.now().plusSeconds(60),
        ).apply { publicId = verificationId }
        whenever(tokens.findByPublicIdAndType(verificationId, "email_verification")).thenReturn(verification)

        service.verifyEmail(VerifyEmailRequest(token = verificationRaw), request, response)
        assertThat(verification.usedAt).isNotNull()
        assertThat(user.emailVerified).isTrue()
        verify(sessions).create(request, response, user, false, 0, "fingerprint", "email_code")

        val (resetId, resetSecret, resetRaw) = crypto.opaqueToken("prt")
        val reset = AccountTokenEntity(
            userId = "usr_1",
            type = "password_reset",
            secretHash = crypto.hashSecret(resetSecret),
            expiresAt = Instant.now().plusSeconds(60),
        ).apply { publicId = resetId }
        whenever(tokens.findByPublicIdAndType(resetId, "password_reset")).thenReturn(reset)

        service.resetPassword(ResetPasswordRequest(resetRaw, "new-password-value"), request)
        assertThat(reset.usedAt).isNotNull()
        assertThat(crypto.matchesPassword("new-password-value", user.passwordHash)).isTrue()
        verify(tokens).invalidate(eq("usr_1"), eq("password_reset"), any())
        verify(sessions).revokeAll("usr_1")
    }

    @Test
    fun `email code verification consumes code and creates a session`() {
        val response = mock<HttpServletResponse>()
        val user = UserEntity(
            tenantId = "tnt_1",
            email = "user@example.com",
            passwordHash = crypto.hashPassword("old-password-value"),
        ).apply { publicId = "usr_1" }
        val token = AccountTokenEntity(
            userId = "usr_1",
            type = "email_verification",
            secretHash = crypto.hashSecret("123456"),
            expiresAt = Instant.now().plusSeconds(60),
        )
        whenever(tenants.current()).thenReturn(tenant)
        whenever(users.findByTenantIdAndEmailIgnoreCase("tnt_1", "user@example.com")).thenReturn(user)
        whenever(tokens.findFirstByUserIdAndTypeAndUsedAtIsNullOrderByCreatedAtDesc("usr_1", "email_verification"))
            .thenReturn(token)
        whenever(request.remoteAddr).thenReturn("127.0.0.1")
        whenever(request.getHeader("User-Agent")).thenReturn("test")
        whenever(risk.assess(user, "127.0.0.1", "test"))
            .thenReturn(RiskResult(0, "allow", emptyList(), "fingerprint"))

        val result = service.verifyEmail(
            VerifyEmailRequest(email = "USER@example.com", code = "123456"),
            request,
            response,
        )

        assertThat(result.userId).isEqualTo("usr_1")
        assertThat(token.usedAt).isNotNull()
        assertThat(user.emailVerified).isTrue()
        verify(tokens).invalidate(eq("usr_1"), eq("email_verification"), any())
        verify(sessions).create(request, response, user, false, 0, "fingerprint", "email_code")
    }

    @Test
    fun `invalid credentials are recorded without disclosing account state`() {
        whenever(tenants.current()).thenReturn(tenant)
        whenever(users.findByTenantIdAndEmailIgnoreCase("tnt_1", "missing@example.com")).thenReturn(null)
        whenever(history.save(any<LoginHistoryEntity>())).thenAnswer { it.arguments[0] }
        whenever(request.getHeader("User-Agent")).thenReturn("test")
        whenever(request.remoteAddr).thenReturn("127.0.0.1")

        assertThatThrownBy {
            service.login(
                LoginRequest("missing@example.com", "wrong-password"),
                request,
                mock<HttpServletResponse>(),
            )
        }
            .isInstanceOf(AppException::class.java)
            .hasMessage("Email or password is incorrect")
        verify(history).save(any<LoginHistoryEntity>())
    }

    @Test
    fun `MFA challenge restores user risk and fingerprint`() {
        val user = UserEntity(tenantId = "tnt_1", email = "user@example.com").apply { publicId = "usr_1" }
        whenever(ephemeral.get("mfa:challenge:challenge")).thenReturn("usr_1|25|fingerprint")
        whenever(users.findByPublicId("usr_1")).thenReturn(user)

        val challenge = service.challenge("challenge")

        assertThat(challenge.first).isSameAs(user)
        assertThat(challenge.second).isEqualTo(25)
        assertThat(challenge.third).isEqualTo("fingerprint")
        service.consumeChallenge("challenge")
        verify(ephemeral).delete("mfa:challenge:challenge")
    }

    @Test
    fun `passkey login creates a strong opaque session`() {
        val response = mock<HttpServletResponse>()
        val user = UserEntity(
            tenantId = "tnt_1",
            email = "user@example.com",
            emailVerifiedAt = Instant.now(),
        ).apply { publicId = "usr_1" }
        whenever(users.findByPublicId("usr_1")).thenReturn(user)
        whenever(request.remoteAddr).thenReturn("127.0.0.1")
        whenever(request.getHeader("User-Agent")).thenReturn("test")
        whenever(risk.assess(user, "127.0.0.1", "test"))
            .thenReturn(RiskResult(25, "allow", listOf("new_device"), "fingerprint"))

        val result = service.loginWithPasskey("usr_1", request, response)

        assertThat(result.userId).isEqualTo("usr_1")
        verify(risk).trust("usr_1", "fingerprint")
        verify(sessions).create(request, response, user, true, 25, "fingerprint", "passkey")
    }

    @Test
    fun `passkey reauthentication upgrades the existing session instead of creating a duplicate`() {
        val response = mock<HttpServletResponse>()
        val user = UserEntity(
            tenantId = "tnt_1",
            email = "user@example.com",
            emailVerifiedAt = Instant.now(),
        ).apply { publicId = "usr_1" }
        whenever(users.findByPublicId("usr_1")).thenReturn(user)
        whenever(request.remoteAddr).thenReturn("127.0.0.1")
        whenever(request.getHeader("User-Agent")).thenReturn("test")
        whenever(risk.assess(user, "127.0.0.1", "test"))
            .thenReturn(RiskResult(0, "allow", emptyList(), "fingerprint"))
        whenever(sessions.fromRequest(request)).thenReturn(
            OdsPrincipal(
                userId = "usr_1",
                tenantId = "tnt_1",
                sessionId = "ses_existing",
                email = user.email,
                role = "admin",
                mfaCompleted = false,
                authenticationMethod = "password",
                authenticatedAt = Instant.now(),
            ),
        )

        val result = service.loginWithPasskey("usr_1", request, response)

        assertThat(result.userId).isEqualTo("usr_1")
        verify(sessions).markPasskeyAuthenticated("ses_existing", 0, "fingerprint")
        verify(sessions, never()).create(any(), any(), any(), any(), any(), any(), any())
    }
}
