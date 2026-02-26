package dev.marcal.mailvault.service

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AppMetaRepository
import org.springframework.security.authentication.LockedException
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class LoginAttemptService(
    private val appMetaRepository: AppMetaRepository,
    private val mailVaultProperties: MailVaultProperties,
) {
    fun ensureLoginAllowed(nowEpochSeconds: Long = Instant.now().epochSecond) {
        val lockedUntil = readLong(KEY_AUTH_LOGIN_LOCKED_UNTIL_EPOCH_SECONDS)
        if (nowEpochSeconds < lockedUntil) {
            throw LockedException("Account temporarily locked")
        }
    }

    @Synchronized
    fun onAuthenticationFailure(nowEpochSeconds: Long = Instant.now().epochSecond) {
        val lockedUntil = readLong(KEY_AUTH_LOGIN_LOCKED_UNTIL_EPOCH_SECONDS)
        if (nowEpochSeconds < lockedUntil) {
            return
        }

        val attempts = readInt(KEY_AUTH_LOGIN_FAILED_ATTEMPTS) + 1
        if (attempts >= maxAttempts()) {
            appMetaRepository.put(KEY_AUTH_LOGIN_LOCKED_UNTIL_EPOCH_SECONDS, (nowEpochSeconds + lockoutSeconds()).toString())
            appMetaRepository.put(KEY_AUTH_LOGIN_FAILED_ATTEMPTS, "0")
            return
        }

        appMetaRepository.put(KEY_AUTH_LOGIN_FAILED_ATTEMPTS, attempts.toString())
    }

    @Synchronized
    fun onAuthenticationSuccess() {
        appMetaRepository.put(KEY_AUTH_LOGIN_FAILED_ATTEMPTS, "0")
        appMetaRepository.put(KEY_AUTH_LOGIN_LOCKED_UNTIL_EPOCH_SECONDS, "0")
    }

    private fun maxAttempts(): Int = mailVaultProperties.authMaxFailedAttempts.coerceAtLeast(1)

    private fun lockoutSeconds(): Long = mailVaultProperties.authLockoutSeconds.coerceAtLeast(1)

    private fun readInt(key: String): Int = appMetaRepository.get(key)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun readLong(key: String): Long = appMetaRepository.get(key)?.toLongOrNull()?.coerceAtLeast(0) ?: 0L

    private companion object {
        const val KEY_AUTH_LOGIN_FAILED_ATTEMPTS = "auth.login.failedAttempts"
        const val KEY_AUTH_LOGIN_LOCKED_UNTIL_EPOCH_SECONDS = "auth.login.lockedUntilEpochSeconds"
    }
}
