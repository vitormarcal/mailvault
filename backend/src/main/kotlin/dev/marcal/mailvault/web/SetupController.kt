package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.SetupBootstrapRequest
import dev.marcal.mailvault.api.SetupStatusResponse
import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.service.AuthBootstrapService
import dev.marcal.mailvault.service.UiLanguageService
import dev.marcal.mailvault.util.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@RestController
@RequestMapping("/api/setup")
class SetupController(
    private val authBootstrapService: AuthBootstrapService,
    private val uiLanguageService: UiLanguageService,
    private val mailVaultProperties: MailVaultProperties,
) {
    @GetMapping("/status")
    fun status(): SetupStatusResponse =
        SetupStatusResponse(
            configured = authBootstrapService.isConfigured(),
            requiresInstallToken = requiresInstallToken(),
        )

    @PostMapping("/bootstrap")
    @ResponseStatus(HttpStatus.CREATED)
    fun bootstrap(
        @RequestBody request: SetupBootstrapRequest,
    ): SetupStatusResponse {
        validateInstallToken(request.installToken)
        authBootstrapService.bootstrap(
            username = request.username,
            password = request.password,
        )
        uiLanguageService.setLanguage(request.language)
        return SetupStatusResponse(configured = true, requiresInstallToken = requiresInstallToken())
    }

    private fun requiresInstallToken(): Boolean = mailVaultProperties.setupBootstrapToken?.isNotBlank() == true

    private fun validateInstallToken(candidate: String?) {
        val expected = mailVaultProperties.setupBootstrapToken?.trim().orEmpty()
        if (expected.isEmpty()) {
            return
        }
        val provided = candidate?.trim().orEmpty()
        val valid =
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                provided.toByteArray(StandardCharsets.UTF_8),
            )
        if (!valid) {
            throw ValidationException("installToken is invalid")
        }
    }
}
