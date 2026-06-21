package uz.ods.sso.oauth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.AuditLogEntity
import uz.ods.sso.persistence.UsedRefreshTokenEntity
import uz.ods.sso.persistence.UsedRefreshTokenRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional

class RotationTrackingAuthorizationServiceTest {
    private val delegate: OAuth2AuthorizationService = mock()
    private val usedTokens: UsedRefreshTokenRepository = mock()
    private val users: UserRepository = mock()
    private val sessions: UserSessionRepository = mock()
    private val audit: AuditService = mock()
    private val crypto = CryptoService(
        OdsProperties(
            sessionSecret = "session-secret-that-is-longer-than-32-characters",
            tokenPepper = "token-pepper-that-is-independent-and-long",
        ),
    )
    private val service = RotationTrackingAuthorizationService(
        delegate,
        usedTokens,
        users,
        sessions,
        crypto,
        audit,
    )
    private val client = RegisteredClient.withId("registered-1")
        .clientId("client-1")
        .clientName("Client")
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .build()

    @Test
    fun `rotation stores only HMAC of previous refresh token`() {
        val previous = authorization("auth-1", "old-refresh")
        val rotated = authorization("auth-1", "new-refresh")
        val user = UserEntity(id = "usr_1", tenantId = "tnt_1", email = "user@example.com")
        whenever(delegate.findById("auth-1")).thenReturn(previous)
        whenever(users.findById("usr_1")).thenReturn(Optional.of(user))
        whenever(usedTokens.findByTokenHash(crypto.hashSecret("old-refresh"))).thenReturn(null)
        whenever(usedTokens.save(any<UsedRefreshTokenEntity>())).thenAnswer { it.arguments[0] as UsedRefreshTokenEntity }

        service.save(rotated)

        val captor = argumentCaptor<UsedRefreshTokenEntity>()
        verify(usedTokens).save(captor.capture())
        assertThat(captor.firstValue.tokenHash).isEqualTo(crypto.hashSecret("old-refresh"))
        assertThat(captor.firstValue.tokenHash).doesNotContain("old-refresh")
        verify(delegate).save(rotated)
    }

    @Test
    fun `reuse revokes authorization and all user sessions`() {
        val used = UsedRefreshTokenEntity(
            tenantId = "tnt_1",
            authorizationId = "auth-1",
            userId = "usr_1",
            clientId = "client-1",
            tokenHash = crypto.hashSecret("old-refresh"),
            expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
        )
        val activeAuthorization = authorization("auth-1", "new-refresh")
        whenever(delegate.findByToken("old-refresh", OAuth2TokenType.REFRESH_TOKEN)).thenReturn(null)
        whenever(usedTokens.findByTokenHash(crypto.hashSecret("old-refresh"))).thenReturn(used)
        whenever(delegate.findById("auth-1")).thenReturn(activeAuthorization)
        whenever(audit.writeSystem(any(), any(), any(), any(), any(), any())).thenReturn(
            AuditLogEntity(tenantId = "tnt_1", eventType = "REFRESH_TOKEN_REUSE_DETECTED", eventHash = "hash"),
        )

        val result = service.findByToken("old-refresh", OAuth2TokenType.REFRESH_TOKEN)

        assertThat(result).isNull()
        assertThat(used.reusedAt).isNotNull()
        verify(sessions).revokeAll(eq("usr_1"), any<Instant>(), isNull())
        verify(delegate).remove(activeAuthorization)
        verify(audit).writeSystem(
            tenantId = "tnt_1",
            eventType = "REFRESH_TOKEN_REUSE_DETECTED",
            actorId = "usr_1",
            subjectId = "usr_1",
            clientId = "client-1",
            details = mapOf("authorization_id" to "auth-1"),
        )
    }

    private fun authorization(id: String, refreshToken: String): OAuth2Authorization {
        val now = Instant.now()
        return OAuth2Authorization.withRegisteredClient(client)
            .id(id)
            .principalName("usr_1")
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .refreshToken(OAuth2RefreshToken(refreshToken, now, now.plus(1, ChronoUnit.HOURS)))
            .build()
    }
}
