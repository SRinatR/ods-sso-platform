package uz.ods.sso.shared.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import org.slf4j.event.KeyValuePair
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component
import java.util.IdentityHashMap

object LoggingSanitizer {
    private const val REDACTED = "[redacted]"
    private const val SENSITIVE_NAME =
        "password|passwd|pwd|access[_-]?token|refresh[_-]?token|id[_-]?token|" +
            "authorization[_-]?code|client[_-]?secret|session[_-]?secret|token[_-]?pepper|" +
            "api[_-]?key|private[_-]?key|otp|totp|backup[_-]?code|cookie|authorization"

    private val privateKey = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val doubleQuotedAssignment = Regex("""(?i)("(?:$SENSITIVE_NAME)"\s*:\s*")[^"]*(")""")
    private val singleQuotedAssignment = Regex("""(?i)('(?:$SENSITIVE_NAME)'\s*:\s*')[^']*(')""")
    private val plainAssignment = Regex("""(?i)((?:$SENSITIVE_NAME)\s*[=:]\s*)([^\s,;&{}\]]+)""")
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
    private val sensitiveKey = Regex("""(?i)^(?:$SENSITIVE_NAME)$""")
    private val sensitivePlaceholderPrefix = Regex("""(?i)(?:$SENSITIVE_NAME)\s*[=:]\s*$""")
    private val placeholder = Regex("""(?<!\\)\{\}""")

    fun sanitize(value: String): String = value
        .replace(privateKey, "[redacted-private-key]")
        .replace(bearer, "Bearer $REDACTED")
        .replace(jwt, "[redacted-jwt]")
        .replace(doubleQuotedAssignment, "$1$REDACTED$2")
        .replace(singleQuotedAssignment, "$1$REDACTED$2")
        .replace(plainAssignment, "$1$REDACTED")
        .replace(email, "[redacted-email]")

    fun sanitizeValue(value: Any?): Any? = when (value) {
        null -> null
        is CharArray, is ByteArray -> REDACTED
        is CharSequence -> sanitize(value.toString())
        is Map<*, *> -> value.entries.associate { (key, item) ->
            val keyText = key.toString()
            keyText to if (isSensitiveKey(keyText)) REDACTED else sanitizeValue(item)
        }
        is Iterable<*> -> value.map(::sanitizeValue)
        is Array<*> -> value.map(::sanitizeValue).toTypedArray()
        is Throwable -> "${value.javaClass.name}: ${sanitize(value.message.orEmpty())}"
        else -> value
    }

    internal fun sanitizeEvent(event: ILoggingEvent, context: LoggerContext): LoggingEvent {
        val throwable = event.throwableProxy?.let(::sanitizeThrowable)
        val clean = LoggingEvent(
            LoggingSanitizer::class.java.name,
            context.getLogger(event.loggerName),
            event.level,
            sanitize(event.message),
            throwable,
            sanitizeArguments(event.message, event.argumentArray),
        )
        clean.instant = event.instant
        clean.sequenceNumber = event.sequenceNumber
        clean.threadName = event.threadName
        clean.mdcPropertyMap = event.mdcPropertyMap.mapValues { (key, value) ->
            if (isSensitiveKey(key)) REDACTED else sanitize(value)
        }
        clean.keyValuePairs = event.keyValuePairs?.map {
            KeyValuePair(it.key, if (isSensitiveKey(it.key)) REDACTED else sanitizeValue(it.value))
        }
        event.markerList?.forEach(clean::addMarker)
        if (event.hasCallerData()) clean.callerData = event.callerData
        return clean
    }

    private fun sanitizeArguments(message: String, arguments: Array<out Any?>?): Array<Any?>? {
        if (arguments == null) return null
        val placeholders = placeholder.findAll(message).toList()
        return arguments.mapIndexed { index, argument ->
            val match = placeholders.getOrNull(index)
            val prefix = match?.let { message.substring(0, it.range.first).takeLast(128) }.orEmpty()
            if (sensitivePlaceholderPrefix.containsMatchIn(prefix)) REDACTED else sanitizeValue(argument)
        }.toTypedArray()
    }

    private fun isSensitiveKey(key: String): Boolean =
        sensitiveKey.matches(key.replace('.', '_'))

    private fun sanitizeThrowable(
        proxy: IThrowableProxy,
        seen: MutableSet<IThrowableProxy> = java.util.Collections.newSetFromMap(IdentityHashMap()),
    ): Throwable {
        if (!seen.add(proxy)) return SanitizedLoggedException("${proxy.className}: [cyclic reference]")
        val clean = SanitizedLoggedException("${proxy.className}: ${sanitize(proxy.message.orEmpty())}")
        clean.stackTrace = proxy.stackTraceElementProxyArray.map { it.stackTraceElement }.toTypedArray()
        proxy.cause?.let { clean.initCause(sanitizeThrowable(it, seen)) }
        proxy.suppressed.orEmpty().forEach { clean.addSuppressed(sanitizeThrowable(it, seen)) }
        return clean
    }
}

private class SanitizedLoggedException(message: String) : RuntimeException(message)

internal class SanitizingAppender(
    private val delegate: Appender<ILoggingEvent>,
    private val loggerContext: LoggerContext,
) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        delegate.doAppend(LoggingSanitizer.sanitizeEvent(event, loggerContext))
    }

    override fun stop() {
        delegate.stop()
        super.stop()
    }
}

@Component
class LoggingSanitizerInstaller : InitializingBean {
    override fun afterPropertiesSet() {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        context.loggerList.forEach { logger ->
            val appenders = mutableListOf<Appender<ILoggingEvent>>()
            val iterator = logger.iteratorForAppenders()
            while (iterator.hasNext()) {
                val appender = iterator.next()
                if (appender !is SanitizingAppender) appenders += appender
            }
            appenders.forEach { delegate ->
                logger.detachAppender(delegate)
                val wrapper = SanitizingAppender(delegate, context).apply {
                    this.context = context
                    name = "SANITIZED_${delegate.name ?: "APPENDER"}"
                    start()
                }
                logger.addAppender(wrapper)
            }
        }
    }
}
