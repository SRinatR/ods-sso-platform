package uz.ods.sso.security

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.shared.AppException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class RateLimitRule(val name: String, val requests: Long, val window: Duration)

@Service
class RateLimiter(
    private val redis: StringRedisTemplate,
    private val crypto: CryptoService,
    private val properties: OdsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val local = ConcurrentHashMap<String, Pair<Long, Long>>()

    fun enforce(rule: RateLimitRule, identity: String) {
        val key = "ratelimit:${rule.name}:${crypto.sha256(identity)}"
        val count = runCatching {
            val current = redis.opsForValue().increment(key) ?: 1
            if (current == 1L) redis.expire(key, rule.window)
            current
        }.getOrElse {
            if (properties.productionLike) {
                log.error("redis_rate_limit_unavailable", it)
                throw AppException(HttpStatus.SERVICE_UNAVAILABLE, "security_dependency_unavailable", "Rate limiting is unavailable")
            }
            val now = System.currentTimeMillis()
            local.compute(key) { _, existing ->
                if (existing == null || existing.second <= now) 1L to (now + rule.window.toMillis())
                else existing.first + 1 to existing.second
            }!!.first
        }
        if (count > rule.requests) {
            throw AppException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_exceeded",
                "Too many requests",
                listOf(mapOf("limit" to rule.requests, "window_seconds" to rule.window.seconds)),
                mapOf("Retry-After" to rule.window.seconds.toString()),
            )
        }
    }

    companion object {
        val REGISTRATION = RateLimitRule("registration", 3, Duration.ofHours(1))
        val LOGIN = RateLimitRule("login", 5, Duration.ofMinutes(1))
        val MFA = RateLimitRule("mfa", 3, Duration.ofMinutes(1))
        val ADMIN = RateLimitRule("admin", 60, Duration.ofMinutes(1))
    }
}
