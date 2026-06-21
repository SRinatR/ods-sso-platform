package uz.ods.sso.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class IdsTest {
    @Test
    fun `UUIDv7 generator is strictly monotonic within one millisecond`() {
        val clock = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC)
        val generator = MonotonicUuidV7Generator(clock)

        val values = List(1_000) { generator.next() }

        assertThat(values).allMatch { it.version() == 7 }
        assertThat(values.zipWithNext()).allMatch { (left, right) -> left < right }
        assertThat(values.toSet()).hasSize(values.size)
    }

    @Test
    fun `public IDs use stable context parameter namespace`() {
        val namespace = IdNamespace("usr")

        val value = context(namespace) { newId() }

        assertThat(value).startsWith("usr_")
        assertThat(value).hasSize(28)
    }
}
