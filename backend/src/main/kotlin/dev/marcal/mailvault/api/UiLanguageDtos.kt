package dev.marcal.mailvault.api

data class UiLanguageResponse(
    val language: String,
)

data class UiLanguageRequest(
    val language: String? = null,
)
