package uz.ods.sso.session

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.shared.AppException
import uz.ods.sso.shared.clientIp
import java.security.Principal
import java.time.Instant
import java.time.temporal.ChronoUnit

data class OdsPrincipal(
    val userId: String,
    val tenantId: String,
    val sessionId: String,
    val email: String,
    val role: String,
    val mfaCompleted: Boolean,
    val authenticationMethod: String,
    val authenticatedAt: Instant,
) : Principal {
    override fun getName(): String = userId
}

data class CurrentPrincipal(
    val user: UserEntity,
    val session: UserSessionEntity,
)

@Service
class SessionService(
    private val sessions: UserSessionRepository,
    private val users: UserRepository,
    private val crypto: CryptoService,
    private val properties: OdsProperties,
) {
    @Transactional
    fun create(
        request: HttpServletRequest,
        response: HttpServletResponse,
        user: UserEntity,
        mfaCompleted: Boolean,
        riskScore: Int,
        deviceId: String?,
        authenticationMethod: String = if (mfaCompleted) "password_totp" else "password",
    ): UserSessionEntity {
        val (id, secret, raw) = crypto.opaqueToken("ses")
        val now = Instant.now()
        val session = sessions.save(
            UserSessionEntity(
                tenantId = user.tenantId,
                userId = user.id,
                secretHash = crypto.hashSecret(secret),
                ipAddress = clientIp(request, properties),
                userAgent = request.getHeader("User-Agent"),
                deviceId = deviceId,
                createdAt = now,
                lastSeenAt = now,
                expiresAt = now.plus(properties.sessionTtl, ChronoUnit.SECONDS),
                mfaCompletedAt = now.takeIf { mfaCompleted },
                authenticationMethod = authenticationMethod,
                riskScore = riskScore,
            ).apply { publicId = id },
        )
        val cookieDomain = properties.sessionCookieDomain.trim().ifBlank { null }
        if (cookieDomain != null) {
            // Remove the legacy host-only cookie before issuing the shared ods.uz cookie.
            // Browsers may otherwise send both values and keep a user in a 401 loop.
            response.addCookie(expiredCookie(null))
        }
        response.addCookie(
            Cookie(COOKIE_NAME, raw).apply {
                isHttpOnly = true
                secure = properties.productionLike
                path = "/"
                cookieDomain?.let { domain = it }
                maxAge = properties.sessionTtl.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                setAttribute("SameSite", "Lax")
            },
        )
        return session
    }

    @Transactional
    fun authenticate(raw: String): OdsPrincipal? {
        val (id, secret) = runCatching { crypto.splitToken(raw, "ses") }.getOrNull() ?: return null
        val session = sessions.findByPublicId(id) ?: return null
        val now = Instant.now()
        if (
            session.revokedAt != null ||
            !session.expiresAt.isAfter(now) ||
            !crypto.secretMatches(secret, session.secretHash)
        ) return null
        val user = users.findByPublicId(session.userId) ?: return null
        if (user.status != "active") return null
        session.lastSeenAt = now
        return OdsPrincipal(
            user.id,
            user.tenantId,
            session.id,
            user.email,
            user.role,
            session.mfaCompletedAt != null,
            session.authenticationMethod,
            session.createdAt,
        )
    }

    fun current(): CurrentPrincipal {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        val legacyPrincipal = authentication.principal as? OdsPrincipal
        val userId = legacyPrincipal?.userId ?: authentication.name
        val sessionId = legacyPrincipal?.sessionId
            ?: authentication.credentials as? String
            ?: throw AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        val user = users.findByPublicId(userId) ?: throw
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        val session = sessions.findByPublicId(sessionId) ?: throw
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        return CurrentPrincipal(user, session)
    }

    fun authorities(principal: OdsPrincipal) = listOf(
        SimpleGrantedAuthority("ROLE_${principal.role.uppercase()}"),
        SimpleGrantedAuthority("TENANT_${principal.tenantId}"),
        FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
            .issuedAt(principal.authenticatedAt)
            .build(),
    ) + when (principal.authenticationMethod) {
        "passkey" -> listOf(SimpleGrantedAuthority("AMR_WEBAUTHN"))
        "password_totp" -> listOf(SimpleGrantedAuthority("AMR_OTP"))
        else -> emptyList()
    }

    @Transactional
    fun revoke(userId: String, sessionId: String): Boolean {
        val session = sessions.findByPublicIdAndUserId(sessionId, userId) ?: return false
        if (session.revokedAt == null) session.revokedAt = Instant.now()
        return true
    }

    @Transactional
    fun revokeAll(userId: String, exceptId: String? = null): Int =
        sessions.revokeAll(userId, Instant.now(), exceptId)

    @Transactional
    fun markStepUp(sessionId: String) {
        val session = sessions.findByPublicId(sessionId) ?: throw
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        session.stepUpAt = Instant.now()
    }

    @Transactional
    fun markMfaCompleted(sessionId: String) {
        val session = sessions.findByPublicId(sessionId) ?: throw
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        session.mfaCompletedAt = Instant.now()
    }

    fun clearCookie(response: HttpServletResponse) {
        val domain = properties.sessionCookieDomain.trim().ifBlank { null }
        response.addCookie(expiredCookie(domain))
        if (domain != null) {
            response.addCookie(expiredCookie(null))
        }
    }

    private fun expiredCookie(cookieDomain: String?) = Cookie(COOKIE_NAME, "").apply {
        isHttpOnly = true
        secure = properties.productionLike
        path = "/"
        cookieDomain?.let { domain = it }
        maxAge = 0
        setAttribute("SameSite", "Lax")
    }

    companion object {
        const val COOKIE_NAME = "ods_session"
    }
}
