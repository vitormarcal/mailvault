package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.SetupBootstrapRequest
import dev.marcal.mailvault.api.SetupStatusResponse
import dev.marcal.mailvault.service.AuthBootstrapService
import dev.marcal.mailvault.service.UiLanguageService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/setup")
class SetupController(
    private val authBootstrapService: AuthBootstrapService,
    private val uiLanguageService: UiLanguageService,
) {
    @GetMapping("/status")
    fun status(): SetupStatusResponse = SetupStatusResponse(configured = authBootstrapService.isConfigured())

    @PostMapping("/bootstrap")
    @ResponseStatus(HttpStatus.CREATED)
    fun bootstrap(@RequestBody request: SetupBootstrapRequest): SetupStatusResponse {
        authBootstrapService.bootstrap(
            username = request.username,
            password = request.password,
        )
        uiLanguageService.setLanguage(request.language)
        return SetupStatusResponse(configured = true)
    }
}
