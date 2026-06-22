package uz.ods.sso.security

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.shared.AppException
import java.time.Duration
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

data class RateLimitRule(val name: String, val requests: Long, val window: Duration)

internal class LocalSlidingWindow {
    private val attempts = ArrayDeque<Long>()

    @Synchronized
    fun record(now: Long, rule: RateLimitRule): Long {
        val windowMillis = rule.window.toMillis()
        val cutoff = now - windowMillis
        while (attempts.isNotEmpty() && attempts.first() <= cutoff) {
            attempts.removeFirst()
        }
        attempts.addLast(now)
        if (attempts.size.toLong() <= rule.requests) return 0
        return ceil((windowMillis - (now - attempts.first())).coerceAtLeast(1).toDouble() / 1000).toLong()
    }
}

@Service
class RateLimiter(
    private val redis: StringRedisTemplate,
    private val crypto: CryptoService,
    private val properties: OdsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val local = ConcurrentHashMap<String, LocalSlidingWindow>()

    fun enforce(rule: RateLimitRule, identity: String, onExceeded: ((Long) -> Unit)? = null) {
        val key = "ratelimit:${rule.name}:${crypto.sha256(identity)}"
        val retryAfter = runCatching {
            redis.execute(
                SLIDING_WINDOW_SCRIPT,
                listOf(key),
                rule.window.toMillis().toString(),
                rule.requests.toString(),
                UUID.randomUUID().toString(),
            ) ?: throw IllegalStateException("Redis rate-limit script returned no result")
        }.getOrElse {
            if (properties.productionLike) {
                log.error("redis_rate_limit_unavailable exception_type={}", it.javaClass.name)
                throw AppException(HttpStatus.SERVICE_UNAVAILABLE, "security_dependency_unavailable", "Rate limiting is unavailable")
            }
            local.computeIfAbsent(key) { LocalSlidingWindow() }.record(System.currentTimeMillis(), rule)
        }
        if (retryAfter > 0) {
            onExceeded?.invoke(retryAfter)
            throw AppException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_exceeded",
                "Too many requests",
                listOf(
                    mapOf(
                        "limit" to rule.requests,
                        "window_seconds" to rule.window.seconds,
                        "retry_after_seconds" to retryAfter,
                    ),
                ),
                mapOf("Retry-After" to retryAfter.toString()),
            )
        }
    }

    companion object {
        internal val SLIDING_WINDOW_SCRIPT = DefaultRedisScript(
            """
            local redis_time = redis.call('TIME')
            local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
            local window = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            local count = redis.call('ZCARD', KEYS[1])
            if count <= limit then
                return 0
            end
            local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            local retry = window
            if oldest[2] then
                retry = math.max(1, window - (now - tonumber(oldest[2])))
            end
            return math.ceil(retry / 1000)
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        val REGISTRATION = RateLimitRule("registration", 3, Duration.ofHours(1))
        val LOGIN = RateLimitRule("login", 5, Duration.ofMinutes(15))
        val MFA = RateLimitRule("mfa", 3, Duration.ofMinutes(1))
        val ADMIN = RateLimitRule("admin", 60, Duration.ofMinutes(1))
    }
}
