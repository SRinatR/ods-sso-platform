package uz.ods.sso.shared.exceptions

import org.springframework.http.HttpStatus

/**
 * Exception thrown when authorization checks fail.
 *
 * This exception SHALL be used when:
 * - An authenticated user attempts to access a resource they don't have permission for
 * - Required scopes/permissions are missing from the access token
 * - Tenant isolation rules prevent access to cross-tenant resources
 * - Role-based access control (RBAC) denies the operation
 * - Resource-level permissions deny the operation
 *
 * Returns HTTP 403 Forbidden status code.
 *
 * NOTE: Use AuthenticationException (401) when credentials are missing or invalid.
 *       Use AuthorizationException (403) when credentials are valid but lack permission.
 *
 * @param message Human-readable authorization error message
 * @param code Application-specific error code
 * @param details Optional contextual information about the denial
 */
class AuthorizationException(
    message: String = "Access denied",
    code: String = "access_denied",
    details: List<Map<String, Any?>> = emptyList(),
) : PlatformException(
    status = HttpStatus.FORBIDDEN,
    code = code,
    message = message,
    details = details
) {
    companion object {
        /**
         * Creates an AuthorizationException for insufficient permissions.
         *
         * @param resource The resource being accessed
         * @param requiredPermission The permission that was required
         */
        fun insufficientPermissions(
            resource: String? = null,
            requiredPermission: String? = null
        ): AuthorizationException {
            val details = mutableListOf<Map<String, Any?>>()
            resource?.let { details.add(mapOf("resource" to it)) }
            requiredPermission?.let { details.add(mapOf("requiredPermission" to it)) }

            return AuthorizationException(
                message = "Insufficient permissions to perform this operation",
                code = "insufficient_permissions",
                details = details
            )
        }

        /**
         * Creates an AuthorizationException for missing OAuth scopes.
         *
         * @param requiredScopes List of scopes required for the operation
         */
        fun missingScopes(requiredScopes: List<String>): AuthorizationException {
            return AuthorizationException(
                message = "Required OAuth scopes are missing",
                code = "insufficient_scope",
                details = listOf(mapOf("requiredScopes" to requiredScopes))
            )
        }

        /**
         * Creates an AuthorizationException for tenant isolation violations.
         *
         * @param tenantId The tenant ID that was being accessed
         */
        fun tenantIsolationViolation(tenantId: String): AuthorizationException {
            return AuthorizationException(
                message = "Access to resources outside your tenant is not allowed",
                code = "tenant_isolation_violation",
                details = listOf(mapOf("attemptedTenantId" to tenantId))
            )
        }

        /**
         * Creates an AuthorizationException for role-based access control denials.
         *
         * @param requiredRole The role required for the operation
         */
        fun roleRequired(requiredRole: String): AuthorizationException {
            return AuthorizationException(
                message = "This operation requires specific role privileges",
                code = "role_required",
                details = listOf(mapOf("requiredRole" to requiredRole))
            )
        }

        /**
         * Creates an AuthorizationException for resource ownership violations.
         */
        fun notResourceOwner(resourceType: String, resourceId: String): AuthorizationException {
            return AuthorizationException(
                message = "You do not have permission to access this resource",
                code = "not_resource_owner",
                details = listOf(
                    mapOf(
                        "resourceType" to resourceType,
                        "resourceId" to resourceId
                    )
                )
            )
        }
    }
}
