package uz.ods.sso.identity

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.EphemeralStore
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.session.SessionService
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
    fun `registration rejects missing terms and duplicate email`() {
        whenever(tenants.current()).thenReturn(tenant)
        assertThatThrownBy {
            service.register(RegisterRequest("user@example.com", "long-enough-password", "User", false), request)
        }.isInstanceOf(AppException::class.java).hasMessage("Terms must be accepted")

        whenever(users.findByTenantIdAndEmailIgnoreCase("tnt_1", "user@example.com")).thenReturn(UserEntity())
        assertThatThrownBy {
            service.register(RegisterRequest("user@example.com", "long-enough-password", "User", true), request)
        }.isInstanceOf(AppException::class.java).hasMessage("Email is already registered")
    }

    @Test
    fun `email verification and password reset consume valid opaque tokens`() {
        val user = UserEntity(
            tenantId = "tnt_1",
            email = "user@example.com",
            passwordHash = crypto.hashPassword("old-password-value"),
        ).apply { publicId = "usr_1" }
        whenever(users.findByPublicId("usr_1")).thenReturn(user)

        val (verificationId, verificationSecret, verificationRaw) = crypto.opaqueToken("evt")
        val verification = AccountTokenEntity(
            userId = "usr_1",
            type = "email_verification",
            secretHash = crypto.hashSecret(verificationSecret),
            expiresAt = Instant.now().plusSeconds(60),
        ).apply { publicId = verificationId }
        whenever(tokens.findByPublicIdAndType(verificationId, "email_verification")).thenReturn(verification)

        service.verifyEmail(verificationRaw, request)
        assertThat(verification.usedAt).isNotNull()
        assertThat(user.emailVerified).isTrue()

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
}
