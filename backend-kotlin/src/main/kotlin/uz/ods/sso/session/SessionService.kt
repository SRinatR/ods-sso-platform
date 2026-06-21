package uz.ods.sso.session

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
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
import java.time.Instant
import java.time.temporal.ChronoUnit

data class OdsPrincipal(
    val userId: String,
    val tenantId: String,
    val sessionId: String,
    val email: String,
    val role: String,
    val mfaCompleted: Boolean,
)

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
    ): UserSessionEntity {
        val (id, secret, raw) = crypto.opaqueToken("ses")
        val now = Instant.now()
        val session = sessions.save(
            UserSessionEntity(
                id = id,
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
                riskScore = riskScore,
            ),
        )
        response.addCookie(
            Cookie(COOKIE_NAME, raw).apply {
                isHttpOnly = true
                secure = properties.productionLike
                path = "/"
                maxAge = properties.sessionTtl.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                setAttribute("SameSite", "Lax")
            },
        )
        return session
    }

    @Transactional
    fun authenticate(raw: String): OdsPrincipal? {
        val (id, secret) = runCatching { crypto.splitToken(raw, "ses") }.getOrNull() ?: return null
        val session = sessions.findById(id).orElse(null) ?: return null
        val now = Instant.now()
        if (
            session.revokedAt != null ||
            !session.expiresAt.isAfter(now) ||
            !crypto.secretMatches(secret, session.secretHash)
        ) return null
        val user = users.findById(session.userId).orElse(null) ?: return null
        if (user.status != "active") return null
        session.lastSeenAt = now
        return OdsPrincipal(
            user.id,
            user.tenantId,
            session.id,
            user.email,
            user.role,
            session.mfaCompletedAt != null,
        )
    }

    fun current(): CurrentPrincipal {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? OdsPrincipal
            ?: throw AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        val user = users.findById(principal.userId).orElseThrow {
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        }
        val session = sessions.findById(principal.sessionId).orElseThrow {
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        }
        return CurrentPrincipal(user, session)
    }

    fun authorities(principal: OdsPrincipal) = listOf(
        SimpleGrantedAuthority("ROLE_${principal.role.uppercase()}"),
        SimpleGrantedAuthority("TENANT_${principal.tenantId}"),
    ) + if (principal.mfaCompleted) listOf(SimpleGrantedAuthority("AMR_OTP")) else emptyList()

    @Transactional
    fun revoke(userId: String, sessionId: String): Boolean {
        val session = sessions.findByIdAndUserId(sessionId, userId) ?: return false
        if (session.revokedAt == null) session.revokedAt = Instant.now()
        return true
    }

    @Transactional
    fun revokeAll(userId: String, exceptId: String? = null): Int =
        sessions.revokeAll(userId, Instant.now(), exceptId)

    @Transactional
    fun markStepUp(sessionId: String) {
        val session = sessions.findById(sessionId).orElseThrow {
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        }
        session.stepUpAt = Instant.now()
    }

    @Transactional
    fun markMfaCompleted(sessionId: String) {
        val session = sessions.findById(sessionId).orElseThrow {
            AppException(HttpStatus.UNAUTHORIZED, "not_authenticated", "Authentication is required")
        }
        session.mfaCompletedAt = Instant.now()
    }

    fun clearCookie(response: HttpServletResponse) {
        response.addCookie(
            Cookie(COOKIE_NAME, "").apply {
                isHttpOnly = true
                secure = properties.productionLike
                path = "/"
                maxAge = 0
                setAttribute("SameSite", "Lax")
            },
        )
    }

    companion object {
        const val COOKIE_NAME = "ods_session"
    }
}
