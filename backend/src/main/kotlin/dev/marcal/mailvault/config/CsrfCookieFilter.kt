package dev.marcal.mailvault.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

class CsrfCookieFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val csrfToken = request.getAttribute(org.springframework.security.web.csrf.CsrfToken::class.java.name)
        if (csrfToken is org.springframework.security.web.csrf.CsrfToken) {
            csrfToken.token
        }
        filterChain.doFilter(request, response)
    }
}
