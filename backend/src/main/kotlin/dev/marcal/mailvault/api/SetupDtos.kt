package dev.marcal.mailvault.api

data class SetupStatusResponse(
    val configured: Boolean,
)

data class SetupBootstrapRequest(
    val username: String?,
    val password: String?,
    val language: String?,
)
