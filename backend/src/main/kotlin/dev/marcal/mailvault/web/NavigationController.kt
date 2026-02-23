package dev.marcal.mailvault.web

import dev.marcal.mailvault.service.SafeNavigationService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class NavigationController(
    private val safeNavigationService: SafeNavigationService,
) {
    @GetMapping("/go")
    fun go(
        @RequestParam url: String,
    ): ResponseEntity<Void> {
        val target = safeNavigationService.safeRedirectTarget(url)
        return ResponseEntity
            .status(302)
            .header(HttpHeaders.LOCATION, target)
            .header("Referrer-Policy", "no-referrer")
            .build()
    }
}
