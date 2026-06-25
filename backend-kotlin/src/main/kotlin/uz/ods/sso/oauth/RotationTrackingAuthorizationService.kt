package uz.ods.sso.oauth

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.audit.AuditService
import uz.ods.sso.persistence.UsedRefreshTokenEntity
import uz.ods.sso.persistence.UsedRefreshTokenRepository
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import java.time.Instant

open class RotationTrackingAuthorizationService(
    private val delegate: OAuth2AuthorizationService,
    private val clients: RegisteredClientRepository,
    private val usedTokens: UsedRefreshTokenRepository,
    private val users: UserRepository,
    private val sessions: UserSessionRepository,
    private val crypto: CryptoService,
    private val audit: AuditService,
) : OAuth2AuthorizationService {
    @Transactional
    open override fun save(authorization: OAuth2Authorization) {
        val previous = delegate.findById(authorization.id)
        val oldRefresh = previous?.refreshToken?.token?.tokenValue
        val newRefresh = authorization.refreshToken?.token?.tokenValue
        val user = users.findByPublicId(authorization.principalName)
        val clientId = clientIdFor(authorization.registeredClientId)
        if (oldRefresh != null && newRefresh != null && oldRefresh != newRefresh) {
            if (user != null && usedTokens.findByTokenHash(crypto.hashSecret(oldRefresh)) == null) {
                usedTokens.save(
                    UsedRefreshTokenEntity(
                        tenantId = user.tenantId,
                        authorizationId = authorization.id,
                        userId = user.id,
                        clientId = clientId,
                        tokenHash = crypto.hashSecret(oldRefresh),
                        expiresAt = previous.refreshToken!!.token.expiresAt ?: Instant.now(),
                    ),
                )
            }
        }
        delegate.save(authorization)
        if (user != null) {
            val previousAccess = previous?.accessToken?.token?.tokenValue
            val currentAccess = authorization.accessToken?.token?.tokenValue
            when {
                previous == null -> audit.writeSystem(
                    tenantId = user.tenantId,
                    eventType = "OAUTH_AUTHORIZATION_CREATED",
                    actorId = user.id,
                    subjectId = user.id,
                    clientId = clientId,
                    details = mapOf("authorization_id" to authorization.id),
                )
                previousAccess == null && currentAccess != null -> audit.writeSystem(
                    tenantId = user.tenantId,
                    eventType = "OAUTH_TOKEN_ISSUED",
                    actorId = user.id,
                    subjectId = user.id,
                    clientId = clientId,
                    details = mapOf("authorization_id" to authorization.id),
                )
                previousAccess != null && currentAccess != null && previousAccess != currentAccess -> audit.writeSystem(
                    tenantId = user.tenantId,
                    eventType = "OAUTH_TOKEN_REFRESHED",
                    actorId = user.id,
                    subjectId = user.id,
                    clientId = clientId,
                    details = mapOf("authorization_id" to authorization.id),
                )
            }
        }
    }

    override fun remove(authorization: OAuth2Authorization) = delegate.remove(authorization)

    override fun findById(id: String): OAuth2Authorization? = delegate.findById(id)

    @Transactional
    open override fun findByToken(token: String, tokenType: OAuth2TokenType?): OAuth2Authorization? {
        val active = delegate.findByToken(token, tokenType)
        if (active != null) return active
        if (tokenType != null && tokenType != OAuth2TokenType.REFRESH_TOKEN) return null

        val used = usedTokens.findByTokenHash(crypto.hashSecret(token)) ?: return null
        if (used.expiresAt.isBefore(Instant.now())) return null
        if (used.reusedAt == null) {
            used.reusedAt = Instant.now()
            sessions.revokeAll(used.userId, Instant.now(), null)
            delegate.findById(used.authorizationId)?.let(delegate::remove)
            audit.writeSystem(
                tenantId = used.tenantId,
                eventType = "REFRESH_TOKEN_REUSE_DETECTED",
                actorId = used.userId,
                subjectId = used.userId,
                clientId = used.clientId,
                details = mapOf("authorization_id" to used.authorizationId),
            )
        }
        return null
    }

    private fun clientIdFor(registeredClientId: String): String =
        clients.findById(registeredClientId)?.clientId ?: registeredClientId
}

@Service
class UsedRefreshTokenCleanup(
    private val repository: UsedRefreshTokenRepository,
) {
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    fun removeExpired() {
        repository.deleteExpired(Instant.now())
    }
}
