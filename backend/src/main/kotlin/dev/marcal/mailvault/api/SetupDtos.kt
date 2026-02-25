package dev.marcal.mailvault.api

data class SetupStatusResponse(
    val configured: Boolean,
    val requiresInstallToken: Boolean = false,
)

data class SetupBootstrapRequest(
    val username: String?,
    val password: String?,
    val language: String?,
    val installToken: String?,
)
