package uz.ods.sso.shared

import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val secureRandom = SecureRandom()
private val encoder = Base64.getUrlEncoder().withoutPadding()

@JvmInline
value class IdNamespace(val prefix: String) {
    init {
        require(prefix.matches(Regex("[a-z][a-z0-9]{1,15}"))) {
            "Public ID prefix must start with a letter and contain only lowercase ASCII letters or digits"
        }
    }
}

context(namespace: IdNamespace)
fun newId(): String {
    val bytes = ByteArray(18)
    secureRandom.nextBytes(bytes)
    return "${namespace.prefix}_${encoder.encodeToString(bytes)}"
}

fun newId(prefix: String): String {
    val namespace = IdNamespace(prefix)
    return context(namespace) { newId() }
}

/**
 * Generates strictly increasing UUIDv7 values inside this process.
 *
 * Java 26's UUID.ofEpochMillis() supplies the RFC 9562 v7 layout and random
 * payload. Reserving a unique millisecond for every generated value makes the
 * resulting UUID sequence strictly ordered even when many IDs are requested in
 * the same wall-clock millisecond or the system clock moves backwards.
 */
class MonotonicUuidV7Generator(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lastEpochMillis = AtomicLong(Long.MIN_VALUE)

    fun next(): UUID {
        val epochMillis = lastEpochMillis.updateAndGet { previous ->
            maxOf(clock.millis(), previous + 1)
        }
        return UUID.ofEpochMillis(epochMillis)
    }
}

object UuidV7 {
    private val generator = MonotonicUuidV7Generator()

    fun next(): UUID = generator.next()
}
