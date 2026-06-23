package uz.ods.sso.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.shared.AppException
import java.time.Duration

class RateLimiterTest {
    @Test
    fun `local counter uses a true sliding window`() {
        val counter = LocalSlidingWindow()
        val rule = RateLimitRule("test", 2, Duration.ofSeconds(1))

        assertThat(counter.record(0, rule)).isZero()
        assertThat(counter.record(500, rule)).isZero()
        assertThat(counter.record(600, rule)).isEqualTo(1)
        assertThat(counter.record(1_001, rule)).isEqualTo(1)
        assertThat(counter.record(1_601, rule)).isZero()
    }

    @Test
    fun `limit overflow invokes callback and returns dynamic retry header`() {
        val limiter = RateLimiter(mock(), CryptoService(properties("test")), properties("test"))
        val rule = RateLimitRule("callback", 1, Duration.ofMinutes(1))
        var callbackRetry = 0L

        limiter.enforce(rule, "identity")
        assertThatThrownBy {
            limiter.enforce(rule, "identity") { callbackRetry = it }
        }.isInstanceOfSatisfying(AppException::class.java) {
            assertThat(it.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            assertThat(it.responseHeaders["Retry-After"]).isEqualTo("60")
            assertThat(it.details.single()["retry_after_seconds"]).isEqualTo(60L)
        }
        assertThat(callbackRetry).isEqualTo(60L)
    }

    @Test
    fun `production fails closed when Redis script is unavailable`() {
        val limiter = RateLimiter(mock<StringRedisTemplate>(), CryptoService(properties("production")), properties("production"))

        assertThatThrownBy { limiter.enforce(RateLimiter.LOGIN, "identity") }
            .isInstanceOfSatisfying(AppException::class.java) {
                assertThat(it.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                assertThat(it.code).isEqualTo("security_dependency_unavailable")
            }
    }

    @Test
    fun `Redis script keeps all sliding window operations atomic`() {
        val script = RateLimiter.SLIDING_WINDOW_SCRIPT.scriptAsString

        assertThat(script).contains(
            "redis.call('TIME')",
            "ZREMRANGEBYSCORE",
            "ZADD",
            "ZCARD",
            "PEXPIRE",
            "math.ceil",
        )
    }

    @Test
    fun `public account limits allow normal retries without hour-long lockout`() {
        assertThat(RateLimiter.REGISTRATION_BURST.requests).isEqualTo(10)
        assertThat(RateLimiter.REGISTRATION_BURST.window).isEqualTo(Duration.ofMinutes(10))
        assertThat(RateLimiter.REGISTRATION_DAILY.requests).isEqualTo(50)
        assertThat(RateLimiter.EMAIL_ACTION.window).isEqualTo(Duration.ofMinutes(15))
    }

    private fun properties(environment: String) = OdsProperties(
        environment = environment,
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
}
