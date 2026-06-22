package uz.ods.sso.shared.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.event.KeyValuePair

class LoggingSanitizerTest {
    @Test
    fun `sanitizer removes credentials tokens email and private keys`() {
        val input = """
            password=plain-secret
            Authorization: Bearer abc.def-123
            access_token=opaque-token
            jwt=eyJhbGciOiJSUzI1NiJ9.payload.signature
            email=person@example.com
            -----BEGIN PRIVATE KEY-----
            secret-key-material
            -----END PRIVATE KEY-----
        """.trimIndent()

        val sanitized = LoggingSanitizer.sanitize(input)

        assertThat(sanitized).doesNotContain(
            "plain-secret",
            "abc.def-123",
            "opaque-token",
            "eyJhbGciOiJSUzI1NiJ9.payload.signature",
            "person@example.com",
            "secret-key-material",
        )
        assertThat(sanitized).contains("[redacted]", "[redacted-email]", "[redacted-private-key]")
    }

    @Test
    fun `sanitized event protects arguments MDC key values and throwable messages`() {
        val context = LoggerContext()
        val event = LoggingEvent(
            javaClass.name,
            context.getLogger("security-test"),
            Level.ERROR,
            "login password={} user={}",
            IllegalStateException("refresh_token=throwable-secret"),
            arrayOf("argument-secret", "person@example.com"),
        ).apply {
            mdcPropertyMap = mapOf(
                "access_token" to "mdc-secret",
                "operation" to "email=operator@example.com",
            )
            keyValuePairs = listOf(
                KeyValuePair("client_secret", "key-value-secret"),
                KeyValuePair("subject", "person@example.com"),
            )
        }

        val sanitized = LoggingSanitizer.sanitizeEvent(event, context)
        val rendered = buildString {
            append(sanitized.formattedMessage)
            append(sanitized.mdcPropertyMap)
            append(sanitized.keyValuePairs)
            append(sanitized.throwableProxy?.message)
        }

        assertThat(rendered).doesNotContain(
            "argument-secret",
            "person@example.com",
            "mdc-secret",
            "operator@example.com",
            "key-value-secret",
            "throwable-secret",
        )
        assertThat(rendered).contains("[redacted]")
    }

    @Test
    fun `appender wrapper sanitizes before delegating to structured output`() {
        val context = LoggerContext().apply { start() }
        val delegate = ListAppender<ILoggingEvent>().apply {
            this.context = context
            start()
        }
        val wrapper = SanitizingAppender(delegate, context).apply {
            this.context = context
            start()
        }
        val event = LoggingEvent(
            javaClass.name,
            context.getLogger("security-test"),
            Level.INFO,
            "authorization={} user={}",
            null,
            arrayOf("Bearer delegated-secret", "person@example.com"),
        ).apply {
            mdcPropertyMap = emptyMap()
        }

        wrapper.doAppend(event)

        assertThat(delegate.list)
            .withFailMessage {
                context.statusManager.copyOfStatusList.joinToString("\n") { it.toString() }
            }
            .hasSize(1)
        assertThat(delegate.list.single().formattedMessage)
            .isEqualTo("authorization=[redacted] user=[redacted-email]")
    }
}
