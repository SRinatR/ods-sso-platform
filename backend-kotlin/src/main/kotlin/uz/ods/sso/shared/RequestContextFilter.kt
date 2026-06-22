package uz.ods.sso.shared

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component("odsRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestContextFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader("X-Request-ID")?.takeIf { it.length <= 64 }
            ?: "req_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        request.setAttribute(REQUEST_ID, requestId)
        response.setHeader("X-Request-ID", requestId)
        response.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
        response.setHeader(
            "Content-Security-Policy",
            "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'",
        )
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        if (
            request.requestURI.startsWith("/api/v1/auth") ||
            request.requestURI.startsWith("/api/v1/admin") ||
            request.requestURI in setOf("/authorize", "/token")
        ) {
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Pragma", "no-cache")
        }

        val started = System.nanoTime()
        MDC.put("request_id", requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            log.info(
                "request_completed method={} path={} status={} duration_ms={}",
                request.method,
                request.requestURI,
                response.status,
                (System.nanoTime() - started) / 1_000_000.0,
            )
            MDC.remove("request_id")
        }
    }

    companion object {
        const val REQUEST_ID = "ods.requestId"
    }
}
