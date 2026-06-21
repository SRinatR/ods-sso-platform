package uz.ods.sso.shared.logging

import org.slf4j.MDC

/**
 * Centralized logging context management for structured logging.
 *
 * This object provides a unified interface for managing Mapped Diagnostic Context (MDC)
 * throughout the SSO platform. MDC enables structured logging with contextual information
 * that is automatically included in all log entries within the same execution context.
 *
 * Context Fields (Requirements 4.2, 4.8):
 * - requestId: Unique correlation ID for request tracing
 * - tenantId: Multi-tenancy context for data isolation
 * - userId: User identity for audit and debugging
 * - timestamp: Event timestamp (automatically added by logging framework)
 *
 * SECURITY NOTE (Requirement 4.7):
 * - NEVER log sensitive data (passwords, tokens, session secrets, encryption keys)
 * - All log messages SHALL be sanitized before output
 * - Use LoggingSanitizer for automatic sanitization
 *
 * Usage Example:
 * ```kotlin
 * LoggingContext.setTenantId("tnt_abc123")
 * LoggingContext.setUserId("usr_xyz789")
 * log.info("User action performed") // Automatically includes tenantId and userId
 * LoggingContext.clear() // Clean up after operation
 * ```
 *
 * @see LoggingSanitizer
 * @see RequestContextFilter
 */
object LoggingContext {

    // MDC keys for structured logging context
    const val REQUEST_ID = "request_id"
    const val TENANT_ID = "tenant_id"
    const val USER_ID = "user_id"
    const val SESSION_ID = "session_id"
    const val IP_ADDRESS = "ip_address"
    const val USER_AGENT = "user_agent"
    const val OPERATION = "operation"

    /**
     * Sets the request ID in MDC context.
     * This is typically called by RequestContextFilter for each incoming request.
     *
     * @param requestId Unique correlation ID for the request (e.g., "req_abc123xyz789")
     */
    fun setRequestId(requestId: String) {
        MDC.put(REQUEST_ID, requestId)
    }

    /**
     * Gets the current request ID from MDC context.
     *
     * @return Current request ID or null if not set
     */
    fun getRequestId(): String? = MDC.get(REQUEST_ID)

    /**
     * Sets the tenant ID in MDC context for multi-tenancy logging.
     *
     * @param tenantId Tenant identifier (e.g., "tnt_abc123xyz789")
     */
    fun setTenantId(tenantId: String) {
        MDC.put(TENANT_ID, tenantId)
    }

    /**
     * Gets the current tenant ID from MDC context.
     *
     * @return Current tenant ID or null if not set
     */
    fun getTenantId(): String? = MDC.get(TENANT_ID)

    /**
     * Sets the user ID in MDC context for user activity tracking.
     *
     * @param userId User identifier (e.g., "usr_abc123xyz789")
     */
    fun setUserId(userId: String) {
        MDC.put(USER_ID, userId)
    }

    /**
     * Gets the current user ID from MDC context.
     *
     * @return Current user ID or null if not set
     */
    fun getUserId(): String? = MDC.get(USER_ID)

    /**
     * Sets the session ID in MDC context for session tracking.
     *
     * @param sessionId Session identifier (e.g., "ses_abc123xyz789")
     */
    fun setSessionId(sessionId: String) {
        MDC.put(SESSION_ID, sessionId)
    }

    /**
     * Gets the current session ID from MDC context.
     *
     * @return Current session ID or null if not set
     */
    fun getSessionId(): String? = MDC.get(SESSION_ID)

    /**
     * Sets the client IP address in MDC context.
     *
     * @param ipAddress Client IP address
     */
    fun setIpAddress(ipAddress: String) {
        MDC.put(IP_ADDRESS, ipAddress)
    }

    /**
     * Gets the current IP address from MDC context.
     *
     * @return Current IP address or null if not set
     */
    fun getIpAddress(): String? = MDC.get(IP_ADDRESS)

    /**
     * Sets the user agent in MDC context.
     *
     * @param userAgent Client user agent string
     */
    fun setUserAgent(userAgent: String) {
        MDC.put(USER_AGENT, userAgent)
    }

    /**
     * Gets the current user agent from MDC context.
     *
     * @return Current user agent or null if not set
     */
    fun getUserAgent(): String? = MDC.get(USER_AGENT)

    /**
     * Sets the current operation name in MDC context for operation tracking.
     *
     * @param operation Operation name (e.g., "user_login", "mfa_verify")
     */
    fun setOperation(operation: String) {
        MDC.put(OPERATION, operation)
    }

    /**
     * Gets the current operation from MDC context.
     *
     * @return Current operation or null if not set
     */
    fun getOperation(): String? = MDC.get(OPERATION)

    /**
     * Clears all MDC context values.
     * This SHOULD be called after completing an operation to prevent context leakage.
     *
     * Note: RequestContextFilter automatically clears REQUEST_ID after request completion.
     */
    fun clear() {
        MDC.clear()
    }

    /**
     * Clears specific context fields while preserving others.
     * Useful when transitioning between operations within the same request.
     *
     * @param keys MDC keys to remove
     */
    fun clearFields(vararg keys: String) {
        keys.forEach { MDC.remove(it) }
    }

    /**
     * Executes a block of code with additional MDC context, then restores previous context.
     *
     * Usage:
     * ```kotlin
     * LoggingContext.withContext("tenant_id" to "tnt_123", "user_id" to "usr_456") {
     *     // Code here will have tenantId and userId in MDC
     *     log.info("Performing operation")
     * }
     * // tenantId and userId are restored to previous values
     * ```
     *
     * @param context Key-value pairs to add to MDC
     * @param block Code block to execute with the context
     * @return Result of the block execution
     */
    inline fun <T> withContext(vararg context: Pair<String, String>, block: () -> T): T {
        // Save current context
        val previousContext = context.associate { (key, _) -> key to MDC.get(key) }

        try {
            // Set new context
            context.forEach { (key, value) -> MDC.put(key, value) }

            // Execute block
            return block()
        } finally {
            // Restore previous context
            previousContext.forEach { (key, value) ->
                if (value != null) {
                    MDC.put(key, value)
                } else {
                    MDC.remove(key)
                }
            }
        }
    }

    /**
     * Gets all current MDC context as a map.
     * Useful for debugging and log enrichment.
     *
     * @return Copy of current MDC context
     */
    fun getAllContext(): Map<String, String> {
        return MDC.getCopyOfContextMap() ?: emptyMap()
    }
}
