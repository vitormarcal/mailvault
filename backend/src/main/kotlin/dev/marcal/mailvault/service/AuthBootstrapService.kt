package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.AppMetaRepository
import dev.marcal.mailvault.util.ValidationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthBootstrapService(
    private val appMetaRepository: AppMetaRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun isConfigured(): Boolean = credentials() != null

    fun credentials(): AuthCredentials? {
        val username = appMetaRepository.get(KEY_AUTH_USER)?.trim().orEmpty()
        val passwordHash = appMetaRepository.get(KEY_AUTH_PASSWORD_HASH)?.trim().orEmpty()
        if (username.isEmpty() || passwordHash.isEmpty()) {
            return null
        }
        return AuthCredentials(username = username, passwordHash = passwordHash)
    }

    @Synchronized
    fun bootstrap(
        username: String?,
        password: String?,
    ) {
        if (isConfigured()) {
            throw ValidationException("Credentials already configured")
        }

        val normalizedUser = username?.trim().orEmpty()
        val rawPassword = password.orEmpty()
        if (normalizedUser.isEmpty()) {
            throw ValidationException("username is required")
        }
        if (rawPassword.isBlank()) {
            throw ValidationException("password is required")
        }

        appMetaRepository.put(KEY_AUTH_USER, normalizedUser)
        val encodedPassword =
            passwordEncoder.encode(rawPassword)
                ?: throw IllegalStateException("Password encoder returned null")
        appMetaRepository.put(KEY_AUTH_PASSWORD_HASH, encodedPassword)
    }

    data class AuthCredentials(
        val username: String,
        val passwordHash: String,
    )

    private companion object {
        const val KEY_AUTH_USER = "auth.user"
        const val KEY_AUTH_PASSWORD_HASH = "auth.passwordHash"
    }
}
