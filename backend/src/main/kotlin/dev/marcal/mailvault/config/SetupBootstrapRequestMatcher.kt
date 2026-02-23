package dev.marcal.mailvault.config

import dev.marcal.mailvault.service.AuthBootstrapService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.RequestMatcher

class SetupBootstrapRequestMatcher(
    private val authBootstrapService: AuthBootstrapService,
) : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean {
        if (authBootstrapService.isConfigured()) {
            return false
        }

        val path = request.requestURI
        val method = request.method
        return (method == "GET" && (path == "/" || path == "/setup" || path == "/setup.html")) ||
            (method == "GET" && path == "/api/setup/status") ||
            (method == "POST" && path == "/api/setup/bootstrap")
    }
}
