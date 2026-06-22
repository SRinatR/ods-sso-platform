package uz.ods.sso.shared.exceptions

import org.springframework.http.HttpStatus

/**
 * Exception thrown when authentication fails.
 *
 * This exception SHALL be used for all authentication failures including:
 * - Invalid credentials (username/password mismatch)
 * - Expired tokens or sessions
 * - Missing authentication credentials
 * - Invalid authentication tokens (JWT, OAuth tokens)
 * - MFA verification failures
 *
 * Returns HTTP 401 Unauthorized status code.
 *
 * SECURITY NOTE: Error messages returned to clients should be generic
 * to prevent information disclosure. Detailed reasons SHALL be logged
 * internally but not exposed in API responses.
 *
 * @param message Generic error message for client (e.g., "Authentication failed")
 * @param code Application-specific error code
 * @param details Optional contextual information (should not include sensitive data)
 */
class AuthenticationException(
    message: String = "Authentication failed",
    code: String = "authentication_failed",
    details: List<Map<String, Any?>> = emptyList(),
) : PlatformException(
    status = HttpStatus.UNAUTHORIZED,
    code = code,
    message = message,
    details = details
) {
    companion object {
        /**
         * Creates an AuthenticationException for invalid credentials.
         * Returns a generic message to prevent user enumeration attacks.
         */
        fun invalidCredentials(): AuthenticationException {
            return AuthenticationException(
                message = "Invalid credentials",
                code = "invalid_credentials"
            )
        }

        /**
         * Creates an AuthenticationException for expired tokens.
         */
        fun expiredToken(tokenType: String = "token"): AuthenticationException {
            return AuthenticationException(
                message = "Authentication token has expired",
                code = "token_expired",
                details = listOf(mapOf("tokenType" to tokenType))
            )
        }

        /**
         * Creates an AuthenticationException for missing credentials.
         */
        fun missingCredentials(): AuthenticationException {
            return AuthenticationException(
                message = "Authentication credentials are required",
                code = "credentials_required"
            )
        }

        /**
         * Creates an AuthenticationException for invalid tokens.
         */
        fun invalidToken(tokenType: String = "token"): AuthenticationException {
            return AuthenticationException(
                message = "Invalid authentication token",
                code = "invalid_token",
                details = listOf(mapOf("tokenType" to tokenType))
            )
        }

        /**
         * Creates an AuthenticationException for MFA verification failures.
         */
        fun mfaVerificationFailed(): AuthenticationException {
            return AuthenticationException(
                message = "Multi-factor authentication verification failed",
                code = "mfa_verification_failed"
            )
        }

        /**
         * Creates an AuthenticationException for account lockout scenarios.
         */
        fun accountLocked(reason: String? = null): AuthenticationException {
            val details = reason?.let { listOf(mapOf("reason" to it)) } ?: emptyList()
            return AuthenticationException(
                message = "Account is locked",
                code = "account_locked",
                details = details
            )
        }
    }
}
