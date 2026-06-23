package uz.ods.sso.operations

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.MailService
import java.time.Instant

data class PublicStatusResponse(
    val status: String,
    val checkedAt: Instant,
    val components: Map<String, String>,
)

@RestController
class OperationsController(
    private val properties: OdsProperties,
    private val jdbc: JdbcOperations,
    private val redis: StringRedisTemplate,
    private val mail: MailService,
) {
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok", "environment" to properties.environment)

    @GetMapping("/ready")
    fun ready(): ResponseEntity<Map<String, String>> {
        val status = componentStatus()
        return ResponseEntity.status(status.statusCode)
            .body(mapOf("status" to if (status.body?.status == "operational") "ready" else "not_ready"))
    }

    @GetMapping("/api/v1/public/status")
    fun publicStatus(): ResponseEntity<PublicStatusResponse> = componentStatus()

    @GetMapping("/privacy")
    fun privacy() = mapOf(
        "title" to "Privacy Policy",
        "status" to "published",
        "url" to "${properties.accountUrl}/privacy",
    )

    @GetMapping("/api/v1/dev/mailbox")
    fun mailbox(@RequestParam email: String): List<Map<String, String>> {
        if (properties.productionLike) return emptyList()
        return mail.outbox.filter { it.recipient.equals(email, ignoreCase = true) }.map {
            mapOf("recipient" to it.recipient, "subject" to it.subject, "text" to it.text)
        }
    }

    private fun componentStatus(): ResponseEntity<PublicStatusResponse> {
        val database = runCatching {
            check(jdbc.queryForObject("select 1", Int::class.java) == 1)
            "operational"
        }.getOrElse { "unavailable" }
        val cache = runCatching {
            redis.opsForValue().set("health:ready", "ok", java.time.Duration.ofSeconds(10))
            "operational"
        }.getOrElse { "unavailable" }
        val operational = database == "operational" && cache == "operational"
        return ResponseEntity.status(if (operational) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                PublicStatusResponse(
                    status = if (operational) "operational" else "degraded",
                    checkedAt = Instant.now(),
                    components = mapOf(
                        "identity" to if (operational) "operational" else "unavailable",
                        "database" to database,
                        "sessions" to cache,
                    ),
                ),
            )
    }
}
