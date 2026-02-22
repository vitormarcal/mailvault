package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.UiLanguageRequest
import dev.marcal.mailvault.api.UiLanguageResponse
import dev.marcal.mailvault.service.UiLanguageService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ui")
class UiLanguageController(
    private val uiLanguageService: UiLanguageService,
) {
    @GetMapping("/language")
    fun getLanguage(): UiLanguageResponse = UiLanguageResponse(language = uiLanguageService.getLanguage())

    @PutMapping("/language")
    fun setLanguage(@RequestBody request: UiLanguageRequest): UiLanguageResponse =
        UiLanguageResponse(language = uiLanguageService.setLanguage(request.language))
}
