package uz.ods.sso.consent

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import uz.ods.sso.audit.AuditService
import uz.ods.sso.persistence.UserConsentEntity
import uz.ods.sso.persistence.UserConsentRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService

class ConsentServiceTest {
    private val consents = mock<UserConsentRepository>()
    private val clients = mock<RegisteredClientRepository>()
    private val authorizationConsents = mock<OAuth2AuthorizationConsentService>()
    private val jdbc = mock<JdbcOperations>()
    private val sessions = mock<SessionService>()
    private val audit = mock<AuditService>()
    private val request = mock<HttpServletRequest>()
    private val service = ConsentService(consents, clients, authorizationConsents, jdbc, sessions, audit)
    private val user = UserEntity(tenantId = "tnt_1", email = "user@example.com").apply { publicId = "usr_1" }
    private val session = UserSessionEntity(tenantId = "tnt_1", userId = "usr_1").apply { publicId = "ses_1" }
    private val principal = CurrentPrincipal(user, session)
    private val client = RegisteredClient.withId("registered-1")
        .clientId("client-1")
        .clientName("Partner")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://partner.example/callback")
        .scope("openid")
        .scope("email")
        .build()

    @Test
    fun `connected applications and details are projected`() {
        whenever(sessions.current()).thenReturn(principal)
        val consent = UserConsentEntity(
            tenantId = "tnt_1",
            userId = "usr_1",
            clientId = "client-1",
            scopes = "email openid",
        ).apply { publicId = "cns_1" }
        whenever(consents.findByUserIdAndStatus("usr_1", "granted")).thenReturn(listOf(consent))
        whenever(clients.findByClientId("client-1")).thenReturn(client)
        whenever(authorizationConsents.findById("registered-1", "usr_1")).thenReturn(
            OAuth2AuthorizationConsent.withId("registered-1", "usr_1").scope("openid").build(),
        )

        assertThat(service.listConnected().single().consentId).isEqualTo("cns_1")
        val details = service.details("client-1", setOf("openid", "email"))
        assertThat(details.clientName).isEqualTo("Partner")
        assertThat(details.newScopes).containsExactly("email")
    }

    @Test
    fun `consent synchronization and revocation update both stores`() {
        whenever(sessions.current()).thenReturn(principal)
        whenever(consents.findByUserIdAndClientId("usr_1", "client-1")).thenReturn(null)
        whenever(consents.save(any<UserConsentEntity>())).thenAnswer { it.arguments[0] }

        service.synchronize("usr_1", "tnt_1", "client-1", setOf("openid", "email"))
        verify(consents).save(any<UserConsentEntity>())

        val entity = UserConsentEntity(
            tenantId = "tnt_1",
            userId = "usr_1",
            clientId = "client-1",
            scopes = "openid",
        ).apply { publicId = "cns_1" }
        val authorizationConsent = OAuth2AuthorizationConsent.withId("registered-1", "usr_1")
            .scope("openid")
            .build()
        whenever(consents.findByPublicIdAndUserId("cns_1", "usr_1")).thenReturn(entity)
        whenever(clients.findByClientId("client-1")).thenReturn(client)
        whenever(authorizationConsents.findById("registered-1", "usr_1")).thenReturn(authorizationConsent)

        assertThat(service.revoke("cns_1", request).ok).isTrue()
        assertThat(entity.status).isEqualTo("revoked")
        verify(authorizationConsents).remove(authorizationConsent)
        verify(jdbc).update(
            "delete from oauth2_authorization where registered_client_id = ? and principal_name = ?",
            "registered-1",
            "usr_1",
        )
    }

    @Test
    fun `mirroring service writes and revokes domain consent`() {
        val delegate = mock<OAuth2AuthorizationConsentService>()
        val users = mock<UserRepository>()
        val mirror = MirroringAuthorizationConsentService(delegate, consents, users, clients)
        val authorizationConsent = OAuth2AuthorizationConsent.withId("registered-1", "usr_1")
            .scope("openid")
            .scope("email")
            .build()
        val entity = UserConsentEntity(tenantId = "tnt_1", userId = "usr_1", clientId = "client-1")
        whenever(users.findByPublicId("usr_1")).thenReturn(user)
        whenever(clients.findById("registered-1")).thenReturn(client)
        whenever(consents.findByUserIdAndClientId("usr_1", "client-1")).thenReturn(entity)

        mirror.save(authorizationConsent)
        assertThat(entity.status).isEqualTo("granted")
        assertThat(entity.scopes).isEqualTo("email openid")

        mirror.remove(authorizationConsent)
        assertThat(entity.status).isEqualTo("revoked")
        assertThat(mirror.findById("registered-1", "usr_1")).isNull()
    }
}
