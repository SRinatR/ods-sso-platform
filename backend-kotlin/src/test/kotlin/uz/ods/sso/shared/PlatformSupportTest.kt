package uz.ods.sso.shared

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import uz.ods.sso.shared.exceptions.AuthenticationException
import uz.ods.sso.shared.exceptions.AuthorizationException
import uz.ods.sso.shared.exceptions.PlatformException
import uz.ods.sso.shared.exceptions.ResourceNotFoundException
import uz.ods.sso.shared.exceptions.ValidationException
import uz.ods.sso.shared.logging.LoggingContext
import uz.ods.sso.config.partnerPermissionsForRole

class PlatformSupportTest {
    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `partner roles map to explicit token permissions`() {
        assertThat(partnerPermissionsForRole("owner"))
            .contains("organization:manage", "members:manage", "applications:manage")
        assertThat(partnerPermissionsForRole("admin")).contains("members:manage", "applications:manage")
        assertThat(partnerPermissionsForRole("editor")).containsExactly("content:read", "content:write")
        assertThat(partnerPermissionsForRole("user")).containsExactly("content:read", "content:use")
        assertThat(partnerPermissionsForRole("viewer")).containsExactly("content:read")
    }

    @Test
    fun `exception factories preserve structured diagnostics`() {
        val authentication = listOf(
            AuthenticationException(),
            AuthenticationException.invalidCredentials(),
            AuthenticationException.expiredToken("refresh_token"),
            AuthenticationException.missingCredentials(),
            AuthenticationException.invalidToken("access_token"),
            AuthenticationException.mfaVerificationFailed(),
            AuthenticationException.accountLocked(),
            AuthenticationException.accountLocked("risk"),
        )
        val authorization = listOf(
            AuthorizationException(),
            AuthorizationException.insufficientPermissions(),
            AuthorizationException.insufficientPermissions("users", "write"),
            AuthorizationException.missingScopes(listOf("openid")),
            AuthorizationException.tenantIsolationViolation("tnt_other"),
            AuthorizationException.roleRequired("admin"),
            AuthorizationException.notResourceOwner("session", "ses_1"),
        )
        val missing = listOf(
            ResourceNotFoundException(),
            ResourceNotFoundException.forResource("Widget", "wid_1"),
            ResourceNotFoundException.userNotFound("usr_1"),
            ResourceNotFoundException.sessionNotFound("ses_1"),
            ResourceNotFoundException.clientNotFound("cli_1"),
            ResourceNotFoundException.tenantNotFound("tnt_1"),
            ResourceNotFoundException.mfaMethodNotFound("mfa_1"),
            ResourceNotFoundException.consentNotFound("cns_1"),
            ResourceNotFoundException.withMessage("gone"),
        )
        val validation = listOf(
            ValidationException("invalid"),
            ValidationException.forField("email", "invalid"),
            ValidationException.forFields(mapOf("email" to "invalid", "name" to "blank")),
        )

        assertThat(authentication).allMatch { it.status == HttpStatus.UNAUTHORIZED }
        assertThat(authorization).allMatch { it.status == HttpStatus.FORBIDDEN }
        assertThat(missing).allMatch { it.status == HttpStatus.NOT_FOUND }
        assertThat(validation).allMatch { it.status == HttpStatus.UNPROCESSABLE_CONTENT }

        val enriched = PlatformException(HttpStatus.CONFLICT, "conflict", "Conflict")
            .withDetails(listOf(mapOf("field" to "email")))
            .withHeaders(mapOf("Retry-After" to "1"))
        assertThat(enriched.details).hasSize(1)
        assertThat(enriched.responseHeaders).containsEntry("Retry-After", "1")
    }

    @Test
    fun `logging context restores prior values`() {
        LoggingContext.setRequestId("req_old")
        LoggingContext.setTenantId("tnt_1")
        LoggingContext.setUserId("usr_1")
        LoggingContext.setSessionId("ses_1")
        LoggingContext.setIpAddress("127.0.0.1")
        LoggingContext.setUserAgent("test")
        LoggingContext.setOperation("login")

        assertThat(LoggingContext.getRequestId()).isEqualTo("req_old")
        assertThat(LoggingContext.getTenantId()).isEqualTo("tnt_1")
        assertThat(LoggingContext.getUserId()).isEqualTo("usr_1")
        assertThat(LoggingContext.getSessionId()).isEqualTo("ses_1")
        assertThat(LoggingContext.getIpAddress()).isEqualTo("127.0.0.1")
        assertThat(LoggingContext.getUserAgent()).isEqualTo("test")
        assertThat(LoggingContext.getOperation()).isEqualTo("login")

        val result = LoggingContext.withContext(
            LoggingContext.REQUEST_ID to "req_new",
            LoggingContext.USER_ID to "usr_2",
        ) {
            assertThat(LoggingContext.getRequestId()).isEqualTo("req_new")
            "done"
        }
        assertThat(result).isEqualTo("done")
        assertThat(LoggingContext.getRequestId()).isEqualTo("req_old")
        assertThat(LoggingContext.getUserId()).isEqualTo("usr_1")
        assertThat(LoggingContext.getAllContext()).containsEntry(LoggingContext.TENANT_ID, "tnt_1")

        LoggingContext.clearFields(LoggingContext.OPERATION, LoggingContext.USER_AGENT)
        assertThat(LoggingContext.getOperation()).isNull()
        assertThat(LoggingContext.getUserAgent()).isNull()
        LoggingContext.clear()
        assertThat(LoggingContext.getAllContext()).isEmpty()
    }

    @Test
    fun `request filter sets correlation and security headers`() {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/login").apply {
            addHeader("X-Request-ID", "req_external")
        }
        val response = MockHttpServletResponse()
        val chain = mock<FilterChain>()

        RequestContextFilter().doFilter(request, response, chain)

        assertThat(request.getAttribute(RequestContextFilter.REQUEST_ID)).isEqualTo("req_external")
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("req_external")
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY")
        verify(chain).doFilter(request, response)
        assertThat(MDC.get("request_id")).isNull()
    }
}
