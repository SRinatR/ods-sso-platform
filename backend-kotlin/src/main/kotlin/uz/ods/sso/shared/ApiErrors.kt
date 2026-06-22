package uz.ods.sso.shared

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.ConstraintViolationException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException
import uz.ods.sso.shared.exceptions.PlatformException

class AppException(
    status: HttpStatus,
    code: String,
    message: String,
    details: List<Map<String, Any?>> = emptyList(),
    responseHeaders: Map<String, String> = emptyMap(),
) : PlatformException(status, code, message, details, responseHeaders)

data class ApiErrorResponse(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val error: String,
    val message: String,
    val details: List<Map<String, Any?>>,
    @field:JsonProperty("request_id")
    val requestId: String,
) {
    companion object {
        fun from(
            request: HttpServletRequest,
            status: HttpStatus,
            code: String,
            message: String,
            details: List<Map<String, Any?>> = emptyList(),
        ) = ApiErrorResponse(
            type = "urn:ods:problem:$code",
            title = status.reasonPhrase,
            status = status.value(),
            detail = message,
            instance = request.requestURI,
            error = code,
            message = message,
            details = details,
            requestId = request.getAttribute(RequestContextFilter.REQUEST_ID)?.toString() ?: "unknown",
        )
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PlatformException::class)
    fun platformError(request: HttpServletRequest, exception: PlatformException): ResponseEntity<ApiErrorResponse> {
        if (exception.status.is5xxServerError) {
            log.error(
                "platform_request_failure code={} status={} method={} path={} exception_type={}",
                exception.code,
                exception.status.value(),
                request.method,
                request.requestURI,
                exception.javaClass.name,
            )
        }
        return response(
            request = request,
            status = exception.status,
            code = exception.code,
            message = exception.message,
            details = exception.details,
            responseHeaders = exception.responseHeaders,
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
        return response(
            request,
            HttpStatus.UNPROCESSABLE_CONTENT,
            "validation_error",
            "Request validation failed",
            details,
        )
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(
        request: HttpServletRequest,
        exception: MissingServletRequestParameterException,
    ): ResponseEntity<ApiErrorResponse> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Required request parameter is missing",
        listOf(mapOf("field" to exception.parameterName)),
    )

    @ExceptionHandler(ConstraintViolationException::class)
    fun constraintViolation(
        request: HttpServletRequest,
        exception: ConstraintViolationException,
    ): ResponseEntity<ApiErrorResponse> = response(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "validation_error",
        "Request validation failed",
        exception.constraintViolations.map {
            mapOf(
                "field" to it.propertyPath.toString(),
                "reason" to it.message,
            )
        },
    )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(
        request: HttpServletRequest,
        exception: MethodArgumentTypeMismatchException,
    ): ResponseEntity<ApiErrorResponse> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request parameter has an invalid value",
        listOf(mapOf("field" to exception.name)),
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableMessage(
        request: HttpServletRequest,
        exception: HttpMessageNotReadableException,
    ): ResponseEntity<ApiErrorResponse> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request body is malformed or unreadable",
    )

    @ExceptionHandler(Exception::class)
    fun unexpected(request: HttpServletRequest, exception: Exception): ResponseEntity<ApiErrorResponse> {
        log.error(
            "unhandled_request_failure method={} path={} exception_type={}",
            request.method,
            request.requestURI,
            exception.javaClass.name,
        )
        return response(
            request,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal_error",
            "An unexpected internal error occurred",
        )
    }

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        code: String,
        message: String,
        details: List<Map<String, Any?>> = emptyList(),
        responseHeaders: Map<String, String> = emptyMap(),
    ): ResponseEntity<ApiErrorResponse> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        responseHeaders.forEach(headers::add)
        return ResponseEntity(
            ApiErrorResponse.from(request, status, code, message, details),
            headers,
            status,
        )
    }
}
