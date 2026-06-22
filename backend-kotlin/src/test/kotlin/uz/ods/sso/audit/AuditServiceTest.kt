package uz.ods.sso.audit

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.AuditLogEntity
import uz.ods.sso.persistence.AuditLogRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.shared.RequestContextFilter

class AuditServiceTest {
    private val repository = mock(AuditLogRepository::class.java)
    private val properties = OdsProperties(
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val service = AuditService(
        repository,
        CryptoService(properties),
        JsonMapper.builder().build(),
        properties,
    )

    @Test
    fun `audit chain includes previous hash and redacts secrets`() {
        val previous = AuditLogEntity(tenantId = "tnt_1", eventType = "PREVIOUS", eventHash = "abc123")
        `when`(repository.findFirstByTenantIdOrderByCreatedAtDesc("tnt_1")).thenReturn(previous)
        `when`(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer { it.arguments[0] }
        val request = mock(HttpServletRequest::class.java)
        `when`(request.getAttribute(RequestContextFilter.REQUEST_ID)).thenReturn("req_1")
        `when`(request.remoteAddr).thenReturn("127.0.0.1")

        service.write(
            "tnt_1",
            request,
            "TOKEN_ISSUED",
            actorId = "usr_1",
            details = mapOf("access_token" to "secret-value", "scope" to "openid"),
        )

        val captor = ArgumentCaptor.forClass(AuditLogEntity::class.java)
        verify(repository).save(captor.capture())
        assertThat(captor.value.previousHash).isEqualTo("abc123")
        assertThat(captor.value.detailsJson).contains("[redacted]").doesNotContain("secret-value")
        assertThat(captor.value.eventHash).hasSize(64)
    }
}
