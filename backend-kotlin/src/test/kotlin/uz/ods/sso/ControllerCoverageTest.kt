package uz.ods.sso

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.jdbc.core.JdbcOperations
import uz.ods.sso.admin.AdminController
import uz.ods.sso.admin.AdminDashboardResponse
import uz.ods.sso.admin.AdminOrganizationResponse
import uz.ods.sso.admin.AdminService
import uz.ods.sso.admin.AdminUserUpdate
import uz.ods.sso.admin.AuditResponse
import uz.ods.sso.admin.OAuthClientCreate
import uz.ods.sso.admin.OAuthClientResponse
import uz.ods.sso.admin.OAuthClientUpdate
import uz.ods.sso.admin.SecurityPolicyResponse
import uz.ods.sso.admin.SecurityPolicyUpdate
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.BackupCodesResponse
import uz.ods.sso.identity.ForgotPasswordRequest
import uz.ods.sso.identity.IdentityController
import uz.ods.sso.identity.IdentityService
import uz.ods.sso.identity.LoginRequest
import uz.ods.sso.identity.LoginResponse
import uz.ods.sso.identity.MailService
import uz.ods.sso.identity.MessageResponse
import uz.ods.sso.identity.MfaChallengeRequest
import uz.ods.sso.identity.ProfileUpdateRequest
import uz.ods.sso.identity.RegisterRequest
import uz.ods.sso.identity.ResendVerificationRequest
import uz.ods.sso.identity.ResetPasswordRequest
import uz.ods.sso.identity.StepUpRequest
import uz.ods.sso.identity.TotpEnableRequest
import uz.ods.sso.identity.VerifyEmailRequest
import uz.ods.sso.mfa.ChallengeResult
import uz.ods.sso.mfa.MfaController
import uz.ods.sso.mfa.MfaService
import uz.ods.sso.operations.OperationsController
import uz.ods.sso.partner.PartnerApplicationCreate
import uz.ods.sso.partner.PartnerAnalyticsResponse
import uz.ods.sso.partner.PartnerAnalyticsSummary
import uz.ods.sso.partner.PartnerApplicationResponse
import uz.ods.sso.partner.PartnerApplicationUpdate
import uz.ods.sso.partner.PartnerController
import uz.ods.sso.partner.PartnerIntegrationMetadata
import uz.ods.sso.partner.PartnerMemberCreate
import uz.ods.sso.partner.PartnerMemberResponse
import uz.ods.sso.partner.PartnerMemberUpdate
import uz.ods.sso.partner.PartnerOrganizationCreate
import uz.ods.sso.partner.PartnerService
import uz.ods.sso.partner.PartnerWorkspaceResponse
import uz.ods.sso.persistence.LoginHistoryEntity
import uz.ods.sso.persistence.LoginHistoryRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.security.StepUpService
import uz.ods.sso.session.AccountController
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.LoginHistoryResponse
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class ControllerCoverageTest {
    private val request = mock<HttpServletRequest>()
    private val response = mock<HttpServletResponse>()
    private val user = UserEntity(
        tenantId = "tnt_1",
        email = "user@example.com",
        passwordHash = "hash",
    ).apply { publicId = "usr_1" }
    private val session = UserSessionEntity(
        tenantId = "tnt_1",
        userId = "usr_1",
        expiresAt = Instant.now().plusSeconds(60),
    ).apply { publicId = "ses_1" }
    private val principal = CurrentPrincipal(user, session)
    private fun properties(
        environment: String = "test",
        accountUrl: String = "http://localhost",
    ) = OdsProperties(
        environment = environment,
        accountUrl = accountUrl,
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )

    @Test
    fun `identity controller delegates account operations`() {
        val identity = mock<IdentityService>()
        val sessions = mock<SessionService>()
        val stepUp = mock<StepUpService>()
        val audit = mock<AuditService>()
        val properties = properties()
        val controller = IdentityController(identity, sessions, stepUp, audit)
        whenever(identity.register(any(), any())).thenReturn(false)
        whenever(identity.login(any(), any(), any())).thenReturn(LoginResponse(userId = "usr_1"))
        whenever(sessions.current()).thenReturn(principal)
        whenever(sessions.revokeAll("usr_1")).thenReturn(2)

        assertThat(controller.register(RegisterRequest("user@example.com", "password-value"), request).statusCode.value())
            .isEqualTo(201)
        assertThat(controller.verifyEmail(VerifyEmailRequest("token"), request).ok).isTrue()
        assertThat(controller.resend(ResendVerificationRequest("user@example.com"), request).ok).isTrue()
        assertThat(controller.forgot(ForgotPasswordRequest("user@example.com"), request).ok).isTrue()
        assertThat(controller.reset(ResetPasswordRequest("token", "new-password-value"), request).ok).isTrue()
        assertThat(controller.login(LoginRequest("user@example.com", "password"), request, response).userId).isEqualTo("usr_1")
        assertThat(controller.me().id).isEqualTo("usr_1")
        assertThat(controller.logout(request, response).ok).isTrue()
        assertThat(controller.logoutAll(request, response).ok).isTrue()
        assertThat(controller.stepUp(StepUpRequest("password", "123456"), request).ok).isTrue()
        verify(stepUp).verifyAndMark(principal, "password", "123456")
    }

    @Test
    fun `MFA and partner controllers delegate all commands`() {
        val mfa = mock<MfaService>()
        val identity = mock<IdentityService>()
        val sessions = mock<SessionService>()
        val stepUp = mock<StepUpService>()
        val audit = mock<AuditService>()
        val limiter = mock<RateLimiter>()
        val properties = properties()
        whenever(mfa.verifyLoginChallenge("challenge", "123456", "totp"))
            .thenReturn(ChallengeResult(user, 25, "fingerprint"))
        whenever(mfa.loginChallengeRateLimitIdentity("challenge")).thenReturn("usr_1")
        whenever(identity.completeLogin(user, request, response, true, 25, "fingerprint"))
            .thenReturn(LoginResponse(userId = "usr_1"))
        whenever(sessions.current()).thenReturn(principal)
        whenever(mfa.setup(user)).thenReturn("secret" to "otpauth://totp/test")
        whenever(mfa.enable(user, "123456")).thenReturn(listOf("backup"))
        whenever(mfa.regenerateBackupCodes("usr_1")).thenReturn(listOf("backup2"))
        val mfaController = MfaController(mfa, identity, sessions, stepUp, audit, limiter)

        assertThat(
            mfaController.verify(MfaChallengeRequest("challenge", "123456", "totp"), request, response).userId,
        ).isEqualTo("usr_1")
        assertThat(mfaController.setup(request).secret).isEqualTo("secret")
        assertThat(mfaController.enable(TotpEnableRequest("123456"), request)).isEqualTo(BackupCodesResponse(listOf("backup")))
        verify(limiter).enforce(RateLimiter.MFA, "setup:usr_1")
        assertThat(mfaController.regenerate(StepUpRequest("password", "123456"), request))
            .isEqualTo(BackupCodesResponse(listOf("backup2")))
        assertThat(mfaController.disable(StepUpRequest("password", "123456"), request).ok).isTrue()
        verify(stepUp, org.mockito.kotlin.times(2)).verifyAndMark(principal, "password", "123456")
        verify(mfa).reset(user)
        verify(sessions).downgradeAfterMfaDisable("usr_1", "ses_1")

        user.lockedUntil = Instant.now().plusSeconds(MfaController.MFA_LOCK_DURATION.seconds)
        whenever(mfa.loginChallengeRateLimitIdentity("limited")).thenReturn("usr_1")
        whenever(mfa.lockLoginChallenge("limited", MfaController.MFA_LOCK_DURATION)).thenReturn(user)
        doAnswer { invocation ->
            invocation.getArgument<(Long) -> Unit>(2).invoke(42)
            throw AppException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_exceeded",
                "Too many requests",
            )
        }.whenever(limiter).enforce(eq(RateLimiter.MFA), any(), any())
        assertThatThrownBy {
            mfaController.verify(MfaChallengeRequest("limited", "123456", "totp"), request, response)
        }.isInstanceOf(AppException::class.java)
        verify(mfa).lockLoginChallenge("limited", MfaController.MFA_LOCK_DURATION)

        val partner = mock<PartnerService>()
        val integration = PartnerIntegrationMetadata(
            "issuer",
            "discovery",
            "authorize",
            "token",
            "userinfo",
            "jwks",
            "logout",
            listOf("openid"),
            listOf("confidential", "public"),
            listOf("client_secret_basic", "none"),
        )
        val workspace = PartnerWorkspaceResponse(null, emptyList(), integration)
        val analytics = PartnerAnalyticsResponse(
            30,
            Instant.now(),
            PartnerAnalyticsSummary(1, 2, 3, 4, 5, 6, 7),
            emptyList(),
            emptyList(),
        )
        val application = PartnerApplicationResponse(
            "appmeta_1",
            "cli_1",
            "secret",
            "App",
            null,
            listOf("https://example.com/callback"),
            listOf("https://example.com/"),
            listOf("openid"),
            "confidential",
            "client_secret_basic",
            true,
            true,
            null,
            false,
            Instant.now(),
        )
        val member = PartnerMemberResponse(
            "mem_1",
            "usr_2",
            "member@example.com",
            "Member",
            "editor",
            "active",
            Instant.now(),
        )
        whenever(partner.workspace(any())).thenReturn(workspace)
        whenever(partner.analytics(any())).thenReturn(analytics)
        whenever(partner.createOrganization(any(), any())).thenReturn(workspace)
        whenever(partner.deleteCurrentOrganization(any())).thenReturn(MessageResponse(message = "Organization deleted"))
        whenever(partner.createApplication(any(), any())).thenReturn(application)
        whenever(partner.updateApplication(any(), any(), any())).thenReturn(application)
        whenever(partner.rotateSecret(any(), any())).thenReturn(application)
        whenever(partner.deleteApplication(any(), any())).thenReturn(MessageResponse(message = "Application deleted"))
        whenever(partner.createMember(any(), any())).thenReturn(member)
        whenever(partner.updateMember(any(), any(), any())).thenReturn(member)
        whenever(partner.deleteMember(any(), any())).thenReturn(MessageResponse(message = "Organization member deleted"))
        val partnerController = PartnerController(partner)
        val organizationCreate = PartnerOrganizationCreate("Org", "org-code", null, null, "owner@example.com")
        val applicationCreate = PartnerApplicationCreate("App", null, listOf("https://example.com/callback"))

        assertThat(partnerController.workspace(request)).isSameAs(workspace)
        assertThat(partnerController.analytics(request)).isSameAs(analytics)
        assertThat(partnerController.createOrganization(organizationCreate, request)).isSameAs(workspace)
        assertThat(partnerController.deleteCurrentOrganization(request).message).isEqualTo("Organization deleted")
        assertThat(partnerController.createApplication(applicationCreate, request)).isSameAs(application)
        assertThat(partnerController.updateApplication("appmeta_1", PartnerApplicationUpdate(enabled = false), request))
            .isSameAs(application)
        assertThat(partnerController.rotateSecret("appmeta_1", request)).isSameAs(application)
        assertThat(partnerController.deleteApplication("appmeta_1", request).message).isEqualTo("Application deleted")
        assertThat(partnerController.createMember(PartnerMemberCreate("member@example.com", "editor"), request))
            .isSameAs(member)
        assertThat(partnerController.updateMember("mem_1", PartnerMemberUpdate(status = "disabled"), request))
            .isSameAs(member)
        assertThat(partnerController.deleteMember("mem_1", request).message).isEqualTo("Organization member deleted")
    }

    @Test
    fun `admin controller delegates all commands`() {
        val admin = mock<AdminService>()
        val controller = AdminController(admin)
        val now = Instant.now()
        val userResponse = uz.ods.sso.identity.UserResponse(
            id = "usr_1",
            email = "user@example.com",
            name = "User",
            phone = null,
            emailVerified = true,
            status = "active",
            role = "user",
            mfaEnabled = false,
            createdAt = now,
        )
        val clientResponse = OAuthClientResponse(
            id = "registered-1",
            clientId = "cli_1",
            name = "Client",
            description = null,
            redirectUris = listOf("https://example.com/callback"),
            allowedScopes = listOf("openid"),
            grantTypes = listOf("authorization_code"),
            tokenEndpointAuthMethod = "client_secret_basic",
            isPublic = false,
            requirePkce = true,
            enabled = true,
            createdAt = now,
            clientSecret = null,
        )
        val organizationResponse = AdminOrganizationResponse(
            id = "org_1",
            slug = "org",
            name = "Org",
            legalName = null,
            websiteUrl = null,
            contactEmail = "owner@example.com",
            status = "active",
            createdAt = now,
        )
        val auditResponse = AuditResponse(
            id = "aud_1",
            eventType = "LOGIN",
            actorId = "usr_1",
            subjectId = "usr_1",
            clientId = "cli_1",
            requestId = "req_1",
            ipAddress = "127.0.0.1",
            details = emptyMap<String, Any?>(),
            previousHash = null,
            eventHash = "hash",
            createdAt = now,
        )
        val policyResponse = SecurityPolicyResponse("password", mapOf("mfa" to true), "usr_admin", now)
        whenever(admin.dashboard(any())).thenReturn(AdminDashboardResponse(1, 1, 1, 1, 0, 1))
        whenever(admin.listUsers(any(), any(), any(), any())).thenReturn(listOf(userResponse))
        whenever(admin.updateUser(any(), any(), any())).thenReturn(userResponse)
        whenever(admin.deleteUser(any(), any())).thenReturn(MessageResponse(message = "User account deleted"))
        whenever(admin.revokeUserSessions(any(), any())).thenReturn(MessageResponse(message = "Sessions revoked"))
        whenever(admin.resetMfa(any(), any())).thenReturn(MessageResponse(message = "MFA reset"))
        whenever(admin.listClients(any())).thenReturn(listOf(clientResponse))
        whenever(admin.createClient(any(), any())).thenReturn(clientResponse)
        whenever(admin.updateClient(any(), any(), any())).thenReturn(clientResponse)
        whenever(admin.rotateSecret(any(), any())).thenReturn(clientResponse)
        whenever(admin.deleteClient(any(), any())).thenReturn(MessageResponse(message = "OAuth client deleted"))
        whenever(admin.listOrganizations(any())).thenReturn(listOf(organizationResponse))
        whenever(admin.deleteOrganization(any(), any())).thenReturn(MessageResponse(message = "Organization deleted"))
        whenever(admin.listSessions(any(), any())).thenReturn(listOf(mapOf("id" to "ses_1")))
        whenever(admin.audit(any(), any(), any(), any())).thenReturn(listOf(auditResponse))
        whenever(admin.policies(any())).thenReturn(listOf(policyResponse))
        whenever(admin.updatePolicy(any(), any(), any())).thenReturn(policyResponse)

        assertThat(controller.dashboard(request).usersTotal).isEqualTo(1)
        assertThat(controller.users(request, "user", -5, 1000)).containsExactly(userResponse)
        assertThat(controller.updateUser("usr_1", AdminUserUpdate(status = "suspended"), request)).isSameAs(userResponse)
        assertThat(controller.deleteUser("usr_1", request).message).isEqualTo("User account deleted")
        assertThat(controller.revokeSessions("usr_1", request).message).isEqualTo("Sessions revoked")
        assertThat(controller.resetMfa("usr_1", request).message).isEqualTo("MFA reset")
        assertThat(controller.clients(request)).containsExactly(clientResponse)
        assertThat(
            controller.createClient(
                OAuthClientCreate("Client", redirectUris = listOf("https://example.com/callback")),
                request,
            ),
        ).isSameAs(clientResponse)
        assertThat(controller.updateClient("registered-1", OAuthClientUpdate(enabled = false), request))
            .isSameAs(clientResponse)
        assertThat(controller.rotateSecret("registered-1", request)).isSameAs(clientResponse)
        assertThat(controller.deleteClient("registered-1", request).message).isEqualTo("OAuth client deleted")
        assertThat(controller.organizations(request)).containsExactly(organizationResponse)
        assertThat(controller.deleteOrganization("org_1", request).message).isEqualTo("Organization deleted")
        assertThat(controller.sessions(request, "usr_1").single()["id"]).isEqualTo("ses_1")
        assertThat(controller.audit(request, "LOGIN", "usr_1", 2000)).containsExactly(auditResponse)
        assertThat(controller.policies(request)).containsExactly(policyResponse)
        assertThat(controller.updatePolicy("password", SecurityPolicyUpdate(mapOf("mfa" to true)), request))
            .isSameAs(policyResponse)

        verify(admin).listUsers(request, "user", 0, 500)
        verify(admin).audit(request, "LOGIN", "usr_1", 1000)
    }

    @Test
    fun `account and operations controllers project persistence state`() {
        val sessions = mock<SessionService>()
        val sessionRepository = mock<UserSessionRepository>()
        val historyRepository = mock<LoginHistoryRepository>()
        val audit = mock<AuditService>()
        val properties = properties()
        whenever(sessions.current()).thenReturn(principal)
        whenever(sessionRepository.findActiveByUserId(any(), any())).thenReturn(listOf(session))
        whenever(sessions.revoke("usr_1", "ses_1")).thenReturn(true)
        val history = LoginHistoryEntity(
            tenantId = "tnt_1",
            userId = "usr_1",
            email = "user@example.com",
            success = true,
        ).apply { publicId = "log_1" }
        whenever(historyRepository.findByUserIdOrderByCreatedAtDesc(any(), any<Pageable>())).thenReturn(listOf(history))
        val account = AccountController(sessions, sessionRepository, historyRepository, audit, properties)

        assertThat(account.updateProfile(ProfileUpdateRequest("User", "+998901234567"), request).name).isEqualTo("User")
        assertThat(account.sessions().single().id).isEqualTo("ses_1")
        assertThat(account.revoke("ses_1", request, response)).isEqualTo(MessageResponse(message = "Session revoked"))
        assertThat(account.history().single()).isEqualTo(
            LoginHistoryResponse("log_1", "user@example.com", true, null, null, null, 0, history.createdAt),
        )

        val jdbc = mock<JdbcOperations>()
        val redis = mock<StringRedisTemplate>()
        val values = mock<ValueOperations<String, String>>()
        val mail = mock<MailService>()
        whenever(jdbc.queryForObject("select 1", Int::class.java)).thenReturn(1)
        whenever(redis.opsForValue()).thenReturn(values)
        whenever(mail.outbox).thenReturn(CopyOnWriteArrayList())
        val operations = OperationsController(properties, jdbc, redis, mail)
        assertThat(operations.health()["status"]).isEqualTo("ok")
        assertThat(operations.ready().body?.get("status")).isEqualTo("ready")
        assertThat(operations.publicStatus().body?.status).isEqualTo("operational")
        assertThat(operations.publicStatus().body?.components)
            .containsEntry("database", "operational")
            .containsEntry("sessions", "operational")
        assertThat(operations.privacy()["status"]).isEqualTo("published")
        assertThat(operations.mailbox("user@example.com")).isEmpty()

        whenever(redis.opsForValue()).thenThrow(IllegalStateException("redis unavailable"))
        assertThat(operations.ready().statusCode.value()).isEqualTo(503)
        assertThat(operations.ready().body?.get("status")).isEqualTo("not_ready")
        assertThat(operations.publicStatus().statusCode.value()).isEqualTo(503)
        assertThat(operations.publicStatus().body?.status).isEqualTo("degraded")
    }
}
