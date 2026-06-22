package uz.ods.sso.shared.exceptions

import org.springframework.http.HttpStatus

/**
 * Base exception class for all platform-specific exceptions.
 *
 * All custom exceptions in the SSO platform SHALL extend from this base class
 * to ensure consistent error handling and propagation throughout the system.
 *
 * @property status HTTP status code to be returned in the response
 * @property code Application-specific error code for client identification
 * @property message Human-readable error message
 * @property details Additional contextual information about the error
 * @property responseHeaders Optional HTTP headers to include in the error response
 *
 * @see ValidationException
 * @see AuthenticationException
 * @see AuthorizationException
 * @see ResourceNotFoundException
 */
open class PlatformException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: List<Map<String, Any?>> = emptyList(),
    val responseHeaders: Map<String, String> = emptyMap(),
) : RuntimeException(message) {

    /**
     * Creates a copy of this exception with additional details.
     * Useful for adding context as the exception propagates up the call stack.
     */
    fun withDetails(additionalDetails: List<Map<String, Any?>>): PlatformException {
        return PlatformException(
            status = this.status,
            code = this.code,
            message = this.message,
            details = this.details + additionalDetails,
            responseHeaders = this.responseHeaders
        )
    }

    /**
     * Creates a copy of this exception with additional response headers.
     */
    fun withHeaders(additionalHeaders: Map<String, String>): PlatformException {
        return PlatformException(
            status = this.status,
            code = this.code,
            message = this.message,
            details = this.details,
            responseHeaders = this.responseHeaders + additionalHeaders
        )
    }
}
