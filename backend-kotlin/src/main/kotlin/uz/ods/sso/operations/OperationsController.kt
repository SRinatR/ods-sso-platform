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
    fun ready(): ResponseEntity<Map<String, String>> = runCatching {
        check(jdbc.queryForObject("select 1", Int::class.java) == 1) { "Database is not ready" }
        redis.opsForValue().set("health:ready", "ok", java.time.Duration.ofSeconds(10))
        ResponseEntity.ok(mapOf("status" to "ready"))
    }.getOrElse {
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("status" to "not_ready"))
    }

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
}
