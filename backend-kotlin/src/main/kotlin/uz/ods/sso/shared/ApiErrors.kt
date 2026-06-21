package uz.ods.sso.shared

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class AppException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: List<Map<String, Any?>> = emptyList(),
    val responseHeaders: Map<String, String> = emptyMap(),
) : RuntimeException(message)

data class ApiErrorResponse(
    val error: String,
    val message: String,
    val details: List<Map<String, Any?>>,
    val request_id: String,
)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(AppException::class)
    fun appError(request: HttpServletRequest, exception: AppException): ResponseEntity<ApiErrorResponse> {
        val headers = HttpHeaders()
        exception.responseHeaders.forEach(headers::add)
        return ResponseEntity(
            payload(request, exception.code, exception.message, exception.details),
            headers,
            exception.status,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(
        request: HttpServletRequest,
        exception: MethodArgumentNotValidException,
    ): ResponseEntity<ApiErrorResponse> {
        val details = exception.bindingResult.fieldErrors.map {
            mapOf("field" to it.field, "reason" to (it.defaultMessage ?: "invalid"))
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
            .body(payload(request, "validation_error", "Request validation failed", details))
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(
        request: HttpServletRequest,
        exception: MissingServletRequestParameterException,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.badRequest().body(
        payload(
            request,
            "invalid_request",
            "Required request parameter is missing",
            listOf(mapOf("field" to exception.parameterName)),
        ),
    )

    @ExceptionHandler(Exception::class)
    fun unexpected(request: HttpServletRequest, exception: Exception): ResponseEntity<ApiErrorResponse> {
        request.servletContext.log("Unhandled request failure", exception)
        return ResponseEntity.internalServerError()
            .body(payload(request, "internal_error", "An unexpected internal error occurred"))
    }

    private fun payload(
        request: HttpServletRequest,
        code: String,
        message: String,
        details: List<Map<String, Any?>> = emptyList(),
    ) = ApiErrorResponse(
        error = code,
        message = message,
        details = details,
        request_id = request.getAttribute(RequestContextFilter.REQUEST_ID)?.toString() ?: "unknown",
    )
}
