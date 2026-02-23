package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.ChangePasswordRequest
import dev.marcal.mailvault.api.ChangePasswordResponse
import dev.marcal.mailvault.service.AuthBootstrapService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authBootstrapService: AuthBootstrapService,
) {
    @PutMapping("/password")
    fun changePassword(
        @RequestBody request: ChangePasswordRequest,
    ): ChangePasswordResponse {
        authBootstrapService.changePassword(
            currentPassword = request.currentPassword,
            newPassword = request.newPassword,
        )
        return ChangePasswordResponse(ok = true)
    }
}
