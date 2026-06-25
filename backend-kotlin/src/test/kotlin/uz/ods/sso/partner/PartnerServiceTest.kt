package uz.ods.sso.partner

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.oauth.OAuthClientProvisioningService
import uz.ods.sso.persistence.AuditClientCountProjection
import uz.ods.sso.persistence.AuditEventCountProjection
import uz.ods.sso.persistence.AuditLogEntity
import uz.ods.sso.persistence.AuditLogRepository
import uz.ods.sso.persistence.PartnerApplicationEntity
import uz.ods.sso.persistence.PartnerApplicationRepository
import uz.ods.sso.persistence.PartnerMembershipEntity
import uz.ods.sso.persistence.PartnerMembershipRepository
import uz.ods.sso.persistence.PartnerOrganizationEntity
import uz.ods.sso.persistence.PartnerOrganizationRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

class PartnerServiceTest {
    private val sessions = mock<SessionService>()
    private val organizations = mock<PartnerOrganizationRepository>()
    private val memberships = mock<PartnerMembershipRepository>()
    private val applications = mock<PartnerApplicationRepository>()
    private val users = mock<UserRepository>()
    private val oauthClients = mock<OAuthClientProvisioningService>()
    private val audit = mock<AuditService>()
    private val auditLogs = mock<AuditLogRepository>()
    private val properties = OdsProperties(
        environment = "test",
        rootDomain = "ods.uz",
        issuer = "https://auth.ods.uz",
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val domains = PartnerDomainService(organizations, properties)
    private val service = PartnerService(
        sessions,
        organizations,
        memberships,
        applications,
        users,
        oauthClients,
        audit,
        auditLogs,
        properties,
        domains,
    )

    @Test
    fun `analytics is scoped to the selected organization and its client ids`() {
        val request = mock<HttpServletRequest>()
        whenever(request.serverName).thenReturn("company.ods.uz")
        val user = UserEntity(tenantId = "tnt_1", email = "owner@example.com").apply { publicId = "usr_owner" }
        val session = UserSessionEntity(tenantId = "tnt_1", userId = "usr_owner").apply { publicId = "ses_1" }
        whenever(sessions.current()).thenReturn(CurrentPrincipal(user, session))
        val organization = PartnerOrganizationEntity(
            tenantId = "tnt_1",
            slug = "company",
            name = "Company",
            contactEmail = "owner@example.com",
        ).apply { publicId = "org_1" }
        val membership = PartnerMembershipEntity(
            organizationId = "org_1",
            userId = "usr_owner",
            role = "owner",
        )
        whenever(memberships.findByUserIdAndStatusOrderByCreatedAtAsc("usr_owner", "active"))
            .thenReturn(listOf(membership))
        whenever(organizations.findByPublicId("org_1")).thenReturn(organization)
        val app = PartnerApplicationEntity(
            organizationId = "org_1",
            registeredClientId = "registered-1",
            clientId = "client-1",
            createdBy = "usr_owner",
        ).apply { publicId = "appmeta_1" }
        whenever(applications.findByOrganizationIdOrderByCreatedAtDesc("org_1")).thenReturn(listOf(app))
        val client = RegisteredClient.withId("registered-1")
            .clientId("client-1")
            .clientName("Tatarlar production")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://api.tatarlar.uz/api/v1/auth/sso/callback")
            .scope("openid")
            .clientSettings(ClientSettings.builder().requireProofKey(true).setting("enabled", true).build())
            .build()
        whenever(oauthClients.findIncludingDisabledById("registered-1")).thenReturn(client)
        whenever(oauthClients.isEnabled(client)).thenReturn(true)
        whenever(
            auditLogs.countClientEventsByType(
                eq("tnt_1"),
                eq(listOf("client-1")),
                any<Collection<String>>(),
                any<Instant>(),
            ),
        ).thenReturn(
            listOf(
                count("client-1", "OAUTH_TOKEN_ISSUED", 7),
                count("client-1", "OAUTH_TOKEN_REFRESHED", 3),
                count("client-1", "CONSENT_GRANTED", 5),
            ),
        )
        whenever(
            auditLogs.countDistinctClientActorsByClient(
                eq("tnt_1"),
                eq(listOf("client-1")),
                eq(setOf("OAUTH_TOKEN_ISSUED")),
                any<Instant>(),
            ),
        ).thenReturn(listOf(clientCount("client-1", 4)))
        whenever(
            auditLogs.countDistinctClientActors(
                eq("tnt_1"),
                eq(listOf("client-1")),
                eq(setOf("OAUTH_TOKEN_ISSUED")),
                any<Instant>(),
            ),
        ).thenReturn(4)
        whenever(
            auditLogs.countOrganizationEvents(
                eq("tnt_1"),
                eq("org_1"),
                any<Collection<String>>(),
                any<Instant>(),
            ),
        ).thenReturn(2)
        val event = AuditLogEntity(
            tenantId = "tnt_1",
            eventType = "OAUTH_TOKEN_ISSUED",
            actorId = "usr_1",
            subjectId = "usr_1",
            clientId = "client-1",
            requestId = "req_1",
            eventHash = "hash",
        ).apply { publicId = "aud_1" }
        whenever(
            auditLogs.findClientEvents(
                eq("tnt_1"),
                eq(listOf("client-1")),
                any<Collection<String>>(),
                any<Instant>(),
                any<Pageable>(),
            ),
        ).thenReturn(listOf(event))
        whenever(
            auditLogs.findOrganizationEvents(
                eq("tnt_1"),
                eq("org_1"),
                any<Collection<String>>(),
                any<Instant>(),
                any<Pageable>(),
            ),
        ).thenReturn(emptyList())

        val analytics = service.analytics(request)

        assertThat(analytics.summary.successfulSsoLogins).isEqualTo(7)
        assertThat(analytics.summary.uniqueUsers).isEqualTo(4)
        assertThat(analytics.summary.configurationChanges).isEqualTo(2)
        assertThat(analytics.applications.single().name).isEqualTo("Tatarlar production")
        assertThat(analytics.recentEvents.single().requestId).isEqualTo("req_1")
    }

    @Test
    fun `workspace returns forbidden when organization exists but current user is not a member`() {
        val request = mock<HttpServletRequest>()
        whenever(request.serverName).thenReturn("tatarlar.ods.uz")
        val user = UserEntity(tenantId = "tnt_1", email = "artur@example.com").apply {
            publicId = "usr_artur"
        }
        val session = UserSessionEntity(tenantId = "tnt_1", userId = "usr_artur").apply {
            publicId = "ses_artur"
        }
        whenever(sessions.current()).thenReturn(CurrentPrincipal(user, session))
        whenever(memberships.findByUserIdAndStatusOrderByCreatedAtAsc("usr_artur", "active"))
            .thenReturn(emptyList())
        whenever(organizations.findBySlugAndStatus("tatarlar", "active"))
            .thenReturn(PartnerOrganizationEntity(slug = "tatarlar", status = "active"))

        val error = assertThrows<AppException> { service.workspace(request) }
        assertThat(error.status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(error.code).isEqualTo("partner_workspace_forbidden")
    }

    private fun count(clientId: String, eventType: String, total: Long) =
        object : AuditEventCountProjection {
            override val clientId = clientId
            override val eventType = eventType
            override val total = total
        }

    private fun clientCount(clientId: String, total: Long) =
        object : AuditClientCountProjection {
            override val clientId = clientId
            override val total = total
        }
}
