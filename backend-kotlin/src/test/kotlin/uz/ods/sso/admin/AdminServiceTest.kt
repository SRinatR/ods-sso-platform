package uz.ods.sso.admin

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import tools.jackson.databind.json.JsonMapper
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.UserResponse
import uz.ods.sso.mfa.MfaService
import uz.ods.sso.persistence.AuditLogEntity
import uz.ods.sso.persistence.AuditLogRepository
import uz.ods.sso.persistence.LoginHistoryRepository
import uz.ods.sso.persistence.SecurityPolicyEntity
import uz.ods.sso.persistence.SecurityPolicyRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import java.time.Instant

class AdminServiceTest {
    private val request = mock<HttpServletRequest>()
    private val guard = mock<AdminGuard>()
    private val users = mock<UserRepository>()
    private val sessions = mock<UserSessionRepository>()
    private val loginHistory = mock<LoginHistoryRepository>()
    private val auditLogs = mock<AuditLogRepository>()
    private val policies = mock<SecurityPolicyRepository>()
    private val clients = mock<RegisteredClientRepository>()
    private val jdbc = mock<JdbcOperations>()
    private val mfa = mock<MfaService>()
    private val sessionService = mock<SessionService>()
    private val audit = mock<AuditService>()
    private val properties = OdsProperties(
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val crypto = CryptoService(properties)
    private val service = AdminService(
        guard,
        users,
        sessions,
        loginHistory,
        auditLogs,
        policies,
        clients,
        jdbc,
        crypto,
        mfa,
        sessionService,
        audit,
        JsonMapper.builder().build(),
        properties,
    )
    private val admin = UserEntity(
        tenantId = "tnt_1",
        email = "admin@example.com",
        role = "admin",
        mfaEnabled = true,
    ).apply { publicId = "usr_admin" }
    private val adminSession = UserSessionEntity(
        tenantId = "tnt_1",
        userId = "usr_admin",
        mfaCompletedAt = Instant.now(),
        stepUpAt = Instant.now(),
    ).apply { publicId = "ses_admin" }
    private val principal = CurrentPrincipal(admin, adminSession)

    @Test
    fun `dashboard and list projections use tenant scoped repositories`() {
        whenever(guard.require(request)).thenReturn(principal)
        whenever(users.countByTenantId("tnt_1")).thenReturn(10)
        whenever(users.countByTenantIdAndStatus("tnt_1", "active")).thenReturn(8)
        whenever(sessions.countByTenantIdAndRevokedAtIsNullAndExpiresAtAfter(eq("tnt_1"), any())).thenReturn(4)
        whenever(jdbc.queryForObject("select count(*) from oauth2_registered_client", Long::class.java)).thenReturn(2)
        whenever(loginHistory.countByTenantIdAndSuccessFalseAndCreatedAtAfter(eq("tnt_1"), any())).thenReturn(3)
        whenever(auditLogs.countByTenantIdAndCreatedAtAfter(eq("tnt_1"), any())).thenReturn(12)
        val projectedUser = UserResponse(
            id = "usr_1",
            email = "user@example.com",
            name = null,
            phone = null,
            emailVerified = false,
            status = "active",
            role = "user",
            mfaEnabled = false,
            createdAt = java.time.Instant.now(),
        )
        whenever(jdbc.query(any<String>(), any<RowMapper<UserResponse>>(), eq("tnt_1"), eq(20), eq(0)))
            .thenReturn(listOf(projectedUser))
        whenever(
            jdbc.query(
                any<String>(),
                any<RowMapper<UserResponse>>(),
                eq("tnt_1"),
                eq("user"),
                eq("user"),
                eq(20),
                eq(0),
            ),
        ).thenReturn(listOf(projectedUser))
        val session = UserSessionEntity(tenantId = "tnt_1", userId = "usr_1").apply { publicId = "ses_1" }
        whenever(sessions.findForAdmin(eq("tnt_1"), eq("usr_1"), any<Pageable>())).thenReturn(listOf(session))
        whenever(auditLogs.search(eq("tnt_1"), eq("LOGIN"), eq("usr_1"), any<Pageable>())).thenReturn(
            listOf(
                AuditLogEntity(
                    tenantId = "tnt_1",
                    eventType = "LOGIN",
                    actorId = "usr_1",
                    requestId = "req_1",
                    detailsJson = "{}",
                    eventHash = "hash",
                ).apply { publicId = "aud_1" },
            ),
        )
        whenever(policies.findByTenantIdOrderByKey("tnt_1")).thenReturn(
            listOf(SecurityPolicyEntity(tenantId = "tnt_1", key = "password", valueJson = "{}")),
        )

        assertThat(service.dashboard(request)).isEqualTo(AdminDashboardResponse(10, 8, 4, 2, 3, 12))
        assertThat(service.listUsers(request, "user", 0, 20).single().id).isEqualTo("usr_1")
        assertThat(service.listUsers(request, null, 0, 20).single().id).isEqualTo("usr_1")
        assertThat(service.listSessions(request, "usr_1").single()["id"]).isEqualTo("ses_1")
        assertThat(service.audit(request, "LOGIN", "usr_1", 20).single().id).isEqualTo("aud_1")
        assertThat(service.policies(request).single().key).isEqualTo("password")
    }

    @Test
    fun `user administration updates status role sessions and MFA`() {
        whenever(guard.require(request)).thenReturn(principal)
        val user = UserEntity(tenantId = "tnt_1", email = "user@example.com").apply { publicId = "usr_1" }
        whenever(users.findByPublicId("usr_1")).thenReturn(user)
        whenever(sessionService.revokeAll("usr_1")).thenReturn(3)

        val updated = service.updateUser("usr_1", AdminUserUpdate(status = "suspended", role = "support"), request)
        assertThat(updated.status).isEqualTo("suspended")
        assertThat(updated.role).isEqualTo("support")
        verify(sessionService).revokeAll("usr_1")

        assertThat(service.revokeUserSessions("usr_1", request).ok).isTrue()
        assertThat(service.resetMfa("usr_1", request).ok).isTrue()
        verify(mfa).reset(user)
        verify(jdbc, times(2)).update("delete from oauth2_authorization where principal_name = ?", "usr_1")
    }

    @Test
    fun `security policy is created and serialized`() {
        whenever(guard.require(request)).thenReturn(principal)
        whenever(policies.findByTenantIdAndKey("tnt_1", "oauth")).thenReturn(null)
        whenever(policies.save(any<SecurityPolicyEntity>())).thenAnswer { it.arguments[0] }

        val response = service.updatePolicy(
            "oauth",
            SecurityPolicyUpdate(mapOf("pkce" to true)),
            request,
        )

        assertThat(response.key).isEqualTo("oauth")
        assertThat(response.value).isEqualTo(mapOf("pkce" to true))
        assertThat(response.updatedBy).isEqualTo("usr_admin")
        verify(policies).save(any<SecurityPolicyEntity>())
    }

    @Test
    fun `admin guard accepts current stepped up administrator`() {
        val sessionApi = mock<SessionService>()
        val limiter = mock<RateLimiter>()
        whenever(sessionApi.current()).thenReturn(principal)
        val guard = AdminGuard(sessionApi, limiter, properties, audit)

        assertThat(guard.require(request)).isSameAs(principal)
        verify(limiter).enforce(RateLimiter.ADMIN, "usr_admin")
    }
}
