package dev.marcal.mailvault.web

import dev.marcal.mailvault.service.AuthBootstrapService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@Controller
class UiController(
    private val authBootstrapService: AuthBootstrapService,
) {
    @GetMapping("/")
    fun home(): String =
        if (authBootstrapService.isConfigured()) {
            "forward:/index.html"
        } else {
            "forward:/setup.html"
        }

    @GetMapping("/setup")
    fun setup(): String =
        if (authBootstrapService.isConfigured()) {
            "redirect:/"
        } else {
            "forward:/setup.html"
        }

    @GetMapping("/login")
    fun login(): String =
        if (authBootstrapService.isConfigured()) {
            "forward:/login.html"
        } else {
            "redirect:/setup"
        }

    @GetMapping("/messages/{id}")
    fun message(@PathVariable id: String): String =
        if (authBootstrapService.isConfigured()) {
            "forward:/message.html"
        } else {
            "redirect:/setup"
        }
}
