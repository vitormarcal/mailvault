package dev.marcal.mailvault.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class ViewerSecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method == "GET" && shouldApplyViewerHeaders(request.requestURI)) {
            response.setHeader(
                "Content-Security-Policy",
                "default-src 'none'; " +
                    "img-src 'self'; " +
                    // message.html still ships embedded styles; keep inline style only.
                    "style-src 'self' 'unsafe-inline'; " +
                    "script-src 'self'; " +
                    "connect-src 'self'; " +
                    "base-uri 'none'; " +
                    "form-action 'none'; " +
                    "frame-ancestors 'none'",
            )
            response.setHeader("Referrer-Policy", "no-referrer")
        }

        filterChain.doFilter(request, response)
    }

    private fun shouldApplyViewerHeaders(uri: String): Boolean = uri == "/message.html" || uri.startsWith("/messages/")
}
