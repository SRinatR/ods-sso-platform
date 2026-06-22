package uz.ods.sso.shared.exceptions

import org.springframework.http.HttpStatus

/**
 * Exception thrown when request validation fails.
 *
 * This exception SHALL be used for all input validation errors including:
 * - Invalid request parameters
 * - Constraint violations
 * - Business rule validation failures
 * - Data format errors
 *
 * Returns HTTP 422 Unprocessable Content status code.
 *
 * @param message Human-readable validation error message
 * @param details List of validation error details (field name, reason, etc.)
 * @param code Application-specific error code (defaults to "validation_error")
 */
class ValidationException(
    message: String,
    details: List<Map<String, Any?>> = emptyList(),
    code: String = "validation_error",
) : PlatformException(
    status = HttpStatus.UNPROCESSABLE_CONTENT,
    code = code,
    message = message,
    details = details
) {
    companion object {
        /**
         * Creates a ValidationException for a single field error.
         *
         * @param field Name of the invalid field
         * @param reason Description of why the field is invalid
         * @param message Overall error message (defaults to "Validation failed")
         */
        fun forField(
            field: String,
            reason: String,
            message: String = "Validation failed"
        ): ValidationException {
            return ValidationException(
                message = message,
                details = listOf(mapOf("field" to field, "reason" to reason))
            )
        }

        /**
         * Creates a ValidationException for multiple field errors.
         *
         * @param fieldErrors Map of field names to error reasons
         * @param message Overall error message (defaults to "Validation failed")
         */
        fun forFields(
            fieldErrors: Map<String, String>,
            message: String = "Validation failed"
        ): ValidationException {
            val details = fieldErrors.map { (field, reason) ->
                mapOf("field" to field, "reason" to reason)
            }
            return ValidationException(
                message = message,
                details = details
            )
        }
    }
}
