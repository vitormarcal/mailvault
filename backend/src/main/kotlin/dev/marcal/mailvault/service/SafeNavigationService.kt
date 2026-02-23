package dev.marcal.mailvault.service

import dev.marcal.mailvault.util.ValidationException
import org.springframework.stereotype.Service
import java.net.URI

@Service
class SafeNavigationService {
    fun safeRedirectTarget(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            throw ValidationException("url is required")
        }

        val uri =
            try {
                URI(trimmed)
            } catch (_: Exception) {
                throw ValidationException("invalid url")
            }

        val scheme = uri.scheme?.lowercase() ?: throw ValidationException("invalid url scheme")
        if (scheme != "http" && scheme != "https") {
            throw ValidationException("invalid url scheme")
        }

        return uri.toString()
    }
}
