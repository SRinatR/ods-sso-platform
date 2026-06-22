package uz.ods.sso.session

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import java.time.Instant

class SessionServiceTest {
    private val properties = OdsProperties(
        environment = "test",
        sessionCookieDomain = "ods.uz",
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val sessions = mock<UserSessionRepository>()
    private val users = mock<UserRepository>()
    private val crypto = CryptoService(properties)
    private val service = SessionService(sessions, users, crypto, properties)
    private val user = UserEntity(
        tenantId = "tnt_1",
        email = "user@example.com",
        role = "user",
    ).apply { publicId = "usr_1" }

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `session creation writes opaque secure cookie and UUID entity`() {
        val request = mock<HttpServletRequest>()
        val response = mock<HttpServletResponse>()
        whenever(request.remoteAddr).thenReturn("127.0.0.1")
        whenever(request.getHeader("User-Agent")).thenReturn("test")
        whenever(sessions.save(any<UserSessionEntity>())).thenAnswer { it.arguments[0] }

        val entity = service.create(request, response, user, true, 25, "device")

        assertThat(entity.internalId.version()).isEqualTo(7)
        assertThat(entity.id).startsWith("ses_")
        assertThat(entity.mfaCompletedAt).isNotNull()
        val cookie = argumentCaptor<Cookie>()
        verify(response).addCookie(cookie.capture())
        assertThat(cookie.firstValue.name).isEqualTo(SessionService.COOKIE_NAME)
        assertThat(cookie.firstValue.isHttpOnly).isTrue()
        assertThat(cookie.firstValue.domain).isEqualTo("ods.uz")
        assertThat(cookie.firstValue.value).startsWith("${entity.id}.")
    }

    @Test
    fun `valid session authenticates and exposes factor timestamp`() {
        val (id, secret, raw) = crypto.opaqueToken("ses")
        val entity = UserSessionEntity(
            tenantId = "tnt_1",
            userId = "usr_1",
            secretHash = crypto.hashSecret(secret),
            createdAt = Instant.now().minusSeconds(10),
            expiresAt = Instant.now().plusSeconds(60),
            mfaCompletedAt = Instant.now(),
        ).apply { publicId = id }
        whenever(sessions.findByPublicId(id)).thenReturn(entity)
        whenever(users.findByPublicId("usr_1")).thenReturn(user)

        val principal = service.authenticate(raw)!!

        assertThat(principal.userId).isEqualTo("usr_1")
        assertThat(principal.sessionId).isEqualTo(id)
        assertThat(principal.mfaCompleted).isTrue()
        assertThat(service.authorities(principal).map { it.authority })
            .contains("ROLE_USER", "TENANT_tnt_1", "FACTOR_PASSWORD", "AMR_OTP")
    }

    @Test
    fun `current principal resolves standard OAuth authentication strings`() {
        val entity = UserSessionEntity(tenantId = "tnt_1", userId = "usr_1").apply { publicId = "ses_1" }
        whenever(users.findByPublicId("usr_1")).thenReturn(user)
        whenever(sessions.findByPublicId("ses_1")).thenReturn(entity)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("usr_1", "ses_1", emptyList())

        val current = service.current()

        assertThat(current.user).isSameAs(user)
        assertThat(current.session).isSameAs(entity)
    }

    @Test
    fun `revocation and step up mutate only owned sessions`() {
        val entity = UserSessionEntity(tenantId = "tnt_1", userId = "usr_1").apply { publicId = "ses_1" }
        whenever(sessions.findByPublicIdAndUserId("ses_1", "usr_1")).thenReturn(entity)
        whenever(sessions.findByPublicId("ses_1")).thenReturn(entity)
        whenever(sessions.revokeAll(eq("usr_1"), any(), isNull())).thenReturn(2)

        assertThat(service.revoke("usr_1", "ses_1")).isTrue()
        assertThat(entity.revokedAt).isNotNull()
        assertThat(service.revokeAll("usr_1")).isEqualTo(2)
        service.markStepUp("ses_1")
        service.markMfaCompleted("ses_1")
        assertThat(entity.stepUpAt).isNotNull()
        assertThat(entity.mfaCompletedAt).isNotNull()

        val response = mock<HttpServletResponse>()
        service.clearCookie(response)
        val cookie = argumentCaptor<Cookie>()
        verify(response, times(2)).addCookie(cookie.capture())
        assertThat(cookie.allValues).allSatisfy { assertThat(it.maxAge).isZero() }
        assertThat(cookie.allValues.map { it.domain }).containsExactly("ods.uz", null)
    }
}
