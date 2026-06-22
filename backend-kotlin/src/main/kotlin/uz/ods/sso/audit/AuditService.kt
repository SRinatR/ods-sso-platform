package uz.ods.sso.audit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.AuditLogEntity
import uz.ods.sso.persistence.AuditLogRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.shared.RequestContextFilter
import uz.ods.sso.shared.clientIp
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Service
class AuditService(
    private val repository: AuditLogRepository,
    private val crypto: CryptoService,
    private val objectMapper: ObjectMapper,
    private val properties: OdsProperties,
) {
    @Transactional
    @Synchronized
    fun write(
        tenantId: String,
        request: HttpServletRequest,
        eventType: String,
        actorId: String? = null,
        subjectId: String? = null,
        clientId: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ): AuditLogEntity {
        return append(
            tenantId = tenantId,
            eventType = eventType,
            actorId = actorId,
            subjectId = subjectId,
            clientId = clientId,
            requestId = request.getAttribute(RequestContextFilter.REQUEST_ID)?.toString() ?: "unknown",
            ipAddress = clientIp(request, properties),
            userAgent = request.getHeader("User-Agent"),
            details = details,
        )
    }

    @Transactional
    @Synchronized
    fun writeSystem(
        tenantId: String,
        eventType: String,
        actorId: String? = null,
        subjectId: String? = null,
        clientId: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ): AuditLogEntity = append(
        tenantId,
        eventType,
        actorId,
        subjectId,
        clientId,
        "system",
        null,
        null,
        details,
    )

    private fun append(
        tenantId: String,
        eventType: String,
        actorId: String?,
        subjectId: String?,
        clientId: String?,
        requestId: String,
        ipAddress: String?,
        userAgent: String?,
        details: Map<String, Any?>,
    ): AuditLogEntity {
        val now = Instant.now()
        val previousHash = repository.findFirstByTenantIdOrderByCreatedAtDesc(tenantId)?.eventHash
        val safeDetails = sanitize(details)
        val detailsJson = objectMapper.writeValueAsString(safeDetails)
        val canonical = listOf(
            previousHash.orEmpty(),
            tenantId,
            eventType,
            actorId.orEmpty(),
            subjectId.orEmpty(),
            clientId.orEmpty(),
            now.toString(),
            detailsJson,
        ).joinToString("|")
        return repository.save(
            AuditLogEntity(
                tenantId = tenantId,
                eventType = eventType,
                actorId = actorId,
                subjectId = subjectId,
                clientId = clientId,
                requestId = requestId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                detailsJson = detailsJson,
                previousHash = previousHash,
                eventHash = crypto.sha256(canonical),
                createdAt = now,
            ),
        )
    }

    private fun sanitize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) ->
            val name = key.toString()
            name to if (name.lowercase() in sensitiveKeys) "[redacted]" else sanitize(item)
        }
        is Iterable<*> -> value.map(::sanitize)
        else -> value
    }

    companion object {
        private val sensitiveKeys = setOf(
            "password",
            "password_hash",
            "access_token",
            "refresh_token",
            "authorization_code",
            "otp",
            "totp",
            "backup_code",
            "client_secret",
            "private_key",
        )
    }
}
