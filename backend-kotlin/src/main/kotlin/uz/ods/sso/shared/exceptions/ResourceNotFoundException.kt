package uz.ods.sso.shared.exceptions

import org.springframework.http.HttpStatus

/**
 * Exception thrown when a requested resource cannot be found.
 *
 * This exception SHALL be used when:
 * - A resource with the specified ID does not exist
 * - An endpoint path is valid but the resource is not found
 * - A referenced entity (user, session, client, etc.) is missing
 *
 * Returns HTTP 404 Not Found status code.
 *
 * NOTE: This is different from an invalid endpoint (which returns 404 from the framework).
 *       This exception is for valid endpoints where the requested resource doesn't exist.
 *
 * @param message Human-readable error message describing what was not found
 * @param code Application-specific error code
 * @param details Optional contextual information about the missing resource
 */
class ResourceNotFoundException(
    message: String = "Resource not found",
    code: String = "resource_not_found",
    details: List<Map<String, Any?>> = emptyList(),
) : PlatformException(
    status = HttpStatus.NOT_FOUND,
    code = code,
    message = message,
    details = details
) {
    companion object {
        /**
         * Creates a ResourceNotFoundException for a specific resource type and ID.
         *
         * @param resourceType Type of resource (e.g., "user", "session", "client")
         * @param resourceId ID of the resource that was not found
         */
        fun forResource(
            resourceType: String,
            resourceId: String
        ): ResourceNotFoundException {
            return ResourceNotFoundException(
                message = "$resourceType not found",
                code = "${resourceType.lowercase()}_not_found",
                details = listOf(
                    mapOf(
                        "resourceType" to resourceType,
                        "resourceId" to resourceId
                    )
                )
            )
        }

        /**
         * Creates a ResourceNotFoundException for a user.
         */
        fun userNotFound(userId: String): ResourceNotFoundException {
            return forResource("User", userId)
        }

        /**
         * Creates a ResourceNotFoundException for a session.
         */
        fun sessionNotFound(sessionId: String): ResourceNotFoundException {
            return forResource("Session", sessionId)
        }

        /**
         * Creates a ResourceNotFoundException for an OAuth client.
         */
        fun clientNotFound(clientId: String): ResourceNotFoundException {
            return forResource("Client", clientId)
        }

        /**
         * Creates a ResourceNotFoundException for a tenant.
         */
        fun tenantNotFound(tenantId: String): ResourceNotFoundException {
            return forResource("Tenant", tenantId)
        }

        /**
         * Creates a ResourceNotFoundException for an MFA method.
         */
        fun mfaMethodNotFound(methodId: String): ResourceNotFoundException {
            return forResource("MFA Method", methodId)
        }

        /**
         * Creates a ResourceNotFoundException for a consent record.
         */
        fun consentNotFound(consentId: String): ResourceNotFoundException {
            return forResource("Consent", consentId)
        }

        /**
         * Creates a ResourceNotFoundException with a custom message.
         */
        fun withMessage(message: String): ResourceNotFoundException {
            return ResourceNotFoundException(message = message)
        }
    }
}
