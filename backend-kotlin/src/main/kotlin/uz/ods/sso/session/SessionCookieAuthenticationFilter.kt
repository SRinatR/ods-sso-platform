package uz.ods.sso.session

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class SessionCookieAuthenticationFilter(
    private val sessionService: SessionService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = request.cookies.orEmpty()
            .asSequence()
            .filter { it.name == SessionService.COOKIE_NAME }
            .mapNotNull { sessionService.authenticate(it.value) }
            .firstOrNull()
        if (principal != null) {
            val authorities = sessionService.authorities(principal)
            val authentication = UsernamePasswordAuthenticationToken.authenticated(
                User(principal.userId, "", authorities),
                principal.sessionId,
                authorities,
            )
            SecurityContextHolder.getContext().authentication = authentication
        }
        filterChain.doFilter(request, response)
    }
}
