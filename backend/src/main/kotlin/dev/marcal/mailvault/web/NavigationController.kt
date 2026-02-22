package dev.marcal.mailvault.web

import dev.marcal.mailvault.service.SafeNavigationService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class NavigationController(
    private val safeNavigationService: SafeNavigationService,
) {
    @GetMapping("/go")
    fun go(@RequestParam url: String): String {
        val target = safeNavigationService.safeRedirectTarget(url)
        return "redirect:$target"
    }
}
