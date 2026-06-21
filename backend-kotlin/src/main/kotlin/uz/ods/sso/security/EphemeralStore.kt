package uz.ods.sso.security

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Service
class EphemeralStore(
    private val redis: StringRedisTemplate,
    private val properties: OdsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val local = ConcurrentHashMap<String, Pair<String, Long>>()

    fun set(key: String, value: String, ttl: Duration) {
        runCatching { redis.opsForValue().set(key, value, ttl) }.onFailure {
            if (properties.productionLike) throw it
            log.warn("redis_ephemeral_fallback key={}", key)
            local[key] = value to (System.currentTimeMillis() + ttl.toMillis())
        }
    }

    fun get(key: String): String? = runCatching { redis.opsForValue().get(key) }.getOrElse {
        if (properties.productionLike) throw it
        local[key]?.takeIf { entry -> entry.second > System.currentTimeMillis() }?.first
    }

    fun delete(key: String) {
        runCatching { redis.delete(key) }.onFailure {
            if (properties.productionLike) throw it
        }
        local.remove(key)
    }
}
