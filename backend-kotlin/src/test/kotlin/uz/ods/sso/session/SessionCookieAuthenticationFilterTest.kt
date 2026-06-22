package uz.ods.sso.session

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant

class SessionCookieAuthenticationFilterTest {
    private val sessions = mock<SessionService>()
    private val filter = SessionCookieAuthenticationFilter(sessions)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `valid domain cookie is used when a stale host cookie is sent first`() {
        val request = mock<HttpServletRequest>()
        val response = mock<HttpServletResponse>()
        val chain = mock<FilterChain>()
        whenever(request.cookies).thenReturn(
            arrayOf(
                Cookie(SessionService.COOKIE_NAME, "stale"),
                Cookie(SessionService.COOKIE_NAME, "valid"),
            ),
        )
        whenever(sessions.authenticate("stale")).thenReturn(null)
        val principal = OdsPrincipal(
            userId = "usr_1",
            tenantId = "tnt_1",
            sessionId = "ses_1",
            email = "user@example.com",
            role = "user",
            mfaCompleted = true,
            authenticationMethod = "password_totp",
            authenticatedAt = Instant.now(),
        )
        whenever(sessions.authenticate("valid")).thenReturn(principal)
        whenever(sessions.authorities(principal)).thenReturn(emptyList())

        filter.doFilter(request, response, chain)

        assertThat(SecurityContextHolder.getContext().authentication?.name).isEqualTo("usr_1")
        verify(sessions).authenticate("stale")
        verify(sessions).authenticate("valid")
        verify(chain).doFilter(any(), any())
    }
}
