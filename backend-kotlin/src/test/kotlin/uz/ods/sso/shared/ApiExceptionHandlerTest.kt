package uz.ods.sso.shared

import jakarta.validation.ConstraintViolationException
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import uz.ods.sso.shared.exceptions.PlatformException

class ApiExceptionHandlerTest {
    private val mvc = MockMvcBuilders.standaloneSetup(FailureController())
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun `legacy application exceptions use backward compatible problem details`() {
        mvc.perform(
            get("/failure")
                .requestAttr(RequestContextFilter.REQUEST_ID, "req_problem_test"),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.type").value("urn:ods:problem:rate_limit_exceeded"))
            .andExpect(jsonPath("$.title").value("Too Many Requests"))
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.detail").value("Too many requests"))
            .andExpect(jsonPath("$.instance").value("/failure"))
            .andExpect(jsonPath("$.error").value("rate_limit_exceeded"))
            .andExpect(jsonPath("$.message").value("Too many requests"))
            .andExpect(jsonPath("$.request_id").value("req_problem_test"))
    }

    @Test
    fun `application exception remains a platform exception`() {
        assertThat(
            AppException(HttpStatus.CONFLICT, "conflict", "Conflict"),
        ).isInstanceOf(PlatformException::class.java)
    }

    @Test
    fun `framework and unexpected failures preserve the problem contract`() {
        val handler = ApiExceptionHandler()
        val request = MockHttpServletRequest("POST", "/api/v1/example").apply {
            setAttribute(RequestContextFilter.REQUEST_ID, "req_framework_test")
        }

        val bindingResult = BeanPropertyBindingResult(Any(), "request")
        bindingResult.addError(FieldError("request", "email", "must be valid"))
        val validation = handler.validation(
            request,
            MethodArgumentNotValidException(mock<MethodParameter>(), bindingResult),
        )
        assertThat(validation.statusCode.value()).isEqualTo(422)
        assertThat(validation.body!!.details.single()).containsEntry("field", "email")

        val missing = handler.missingParameter(
            request,
            MissingServletRequestParameterException("limit", "integer"),
        )
        assertThat(missing.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val constraint = handler.constraintViolation(request, ConstraintViolationException(emptySet()))
        assertThat(constraint.statusCode.value()).isEqualTo(422)

        val mismatch = handler.typeMismatch(
            request,
            MethodArgumentTypeMismatchException(
                "invalid",
                Int::class.java,
                "limit",
                mock<MethodParameter>(),
                IllegalArgumentException("invalid"),
            ),
        )
        assertThat(mismatch.body!!.details.single()).containsEntry("field", "limit")

        val unreadable = handler.unreadableMessage(
            request,
            HttpMessageNotReadableException("invalid json", mock<HttpInputMessage>()),
        )
        assertThat(unreadable.body!!.error).isEqualTo("invalid_request")

        val unavailable = handler.platformError(
            request,
            PlatformException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "dependency_unavailable",
                "Dependency is unavailable",
            ),
        )
        assertThat(unavailable.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)

        val unexpected = handler.unexpected(request, IllegalStateException("internal detail"))
        assertThat(unexpected.body!!.detail).isEqualTo("An unexpected internal error occurred")
        assertThat(unexpected.body!!.requestId).isEqualTo("req_framework_test")
    }

    @RestController
    private class FailureController {
        @GetMapping("/failure")
        fun failure(response: HttpServletResponse): Nothing {
            response.contentType = "text/javascript"
            throw AppException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_exceeded",
                "Too many requests",
                details = listOf(mapOf("limit" to 5)),
                responseHeaders = mapOf("Retry-After" to "60"),
            )
        }
    }
}
