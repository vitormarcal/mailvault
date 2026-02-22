package dev.marcal.mailvault.api

data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String,
)
